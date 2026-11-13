package com.feilv.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feilv.common.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Snapshot-bound, process-local cache. Never log credentials, requests or responses. */
@Component
public class WechatAccessTokenProvider {
    private static final URI ENDPOINT = URI.create("https://api.weixin.qq.com/cgi-bin/stable_token");
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Clock clock;
    private final Map<CacheKey, Slot> cache = new ConcurrentHashMap<>();

    @Autowired
    public WechatAccessTokenProvider(ObjectMapper mapper) {
        this(mapper, defaultHttpClient(), Clock.systemUTC());
    }

    WechatAccessTokenProvider(ObjectMapper mapper, HttpClient http) {
        this(mapper, http, Clock.systemUTC());
    }

    WechatAccessTokenProvider(ObjectMapper mapper, HttpClient http, Clock clock) {
        this.mapper = mapper;
        this.http = http;
        this.clock = clock;
    }

    static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    // One lock per credential fingerprint: both normal expiry and rejected-token refresh are single-flight.
    AccessToken get(WechatConfigService.LoginCredentials current) {
        Slot slot = cache.computeIfAbsent(key(current), ignored -> new Slot());
        synchronized (slot) {
            if (usable(slot.token)) return slot.token;
            return slot.token = fetch(current, false);
        }
    }

    AccessToken refreshRejected(WechatConfigService.LoginCredentials current, AccessToken rejected) {
        Slot slot = cache.computeIfAbsent(key(current), ignored -> new Slot());
        synchronized (slot) {
            // A delayed failure from an older request must not invalidate/force-refresh a newer token.
            boolean forceRefresh = slot.token == rejected;
            if (forceRefresh) slot.token = null;
            if (usable(slot.token)) return slot.token;
            return slot.token = fetch(current, forceRefresh);
        }
    }

    void invalidate(WechatConfigService.LoginCredentials current, AccessToken rejected) {
        Slot slot = cache.get(key(current));
        if (slot == null) return;
        synchronized (slot) {
            if (slot.token == rejected) slot.token = null;
        }
    }

    private boolean usable(AccessToken token) {
        return token != null && clock.instant().isBefore(token.refreshAt);
    }

    private AccessToken fetch(WechatConfigService.LoginCredentials current, boolean forceRefresh) {
        try {
            String payload = mapper.writeValueAsString(Map.of("grant_type", "client_credential",
                    "appid", current.appId(), "secret", current.secret(), "force_refresh", forceRefresh));
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw invalidResponse();
            JsonNode body = readObject(mapper, response.body());
            if (errorCode(body, false) != 0) throw invalidResponse();
            JsonNode value = body.path("access_token");
            JsonNode ttl = body.path("expires_in");
            if (!value.isTextual() || !value.textValue().matches("[\\x21-\\x7E]{1,4096}")
                    || !ttl.isIntegralNumber() || !ttl.canConvertToInt() || ttl.intValue() <= 0) {
                throw invalidResponse();
            }
            long seconds = ttl.intValue();
            long earlyExpiry = Math.min(60, Math.max(1, seconds / 10));
            return new AccessToken(value.textValue(), clock.instant().plusSeconds(seconds - earlyExpiry));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.of(502, "微信服务请求中断，请重试");
        } catch (BizException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Deliberately do not attach causes or expose messages: they can contain credentials/raw JSON.
            throw invalidResponse();
        }
    }

    static JsonNode readObject(ObjectMapper mapper, String raw) throws IOException {
        if (raw == null || raw.isBlank() || raw.length() > 65_536) throw invalidResponse();
        JsonNode body = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(raw);
        if (body == null || !body.isObject()) throw invalidResponse();
        return body;
    }

    static int errorCode(JsonNode body, boolean required) {
        JsonNode error = body.get("errcode");
        if (error == null && !required) return 0;
        if (error == null || !error.isIntegralNumber() || !error.canConvertToInt()) throw invalidResponse();
        return error.intValue();
    }

    private static CacheKey key(WechatConfigService.LoginCredentials current) {
        if (current == null || current.appId() == null || current.appId().isBlank()
                || current.secret() == null || current.secret().isBlank()) {
            throw BizException.of(503, "微信登录未配置，请联系管理员");
        }
        try {
            // Cache keys retain no plaintext secret; appid and secret rotation isolate all in-flight snapshots.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(current.appId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            String fingerprint = HexFormat.of().formatHex(digest.digest(current.secret().getBytes(StandardCharsets.UTF_8)));
            return new CacheKey(current.appId(), fingerprint);
        } catch (NoSuchAlgorithmException e) {
            throw BizException.of(503, "微信服务暂不可用");
        }
    }

    private static BizException invalidResponse() {
        return BizException.of(502, "微信凭据服务暂不可用，请稍后重试");
    }

    private record CacheKey(String appId, String credentialFingerprint) {}
    private static final class Slot { private AccessToken token; }

    static final class AccessToken {
        private final String value;
        private final Instant refreshAt;
        private AccessToken(String value, Instant refreshAt) { this.value = value; this.refreshAt = refreshAt; }
        String value() { return value; }
        @Override public String toString() { return "AccessToken[redacted]"; }
    }
}
