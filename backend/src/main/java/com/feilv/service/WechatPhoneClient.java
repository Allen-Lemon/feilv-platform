package com.feilv.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feilv.common.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Component
public class WechatPhoneClient {
    private final WechatAccessTokenProvider tokenProvider;
    private final ObjectMapper mapper;
    private final HttpClient http;

    @Autowired
    public WechatPhoneClient(WechatAccessTokenProvider tokenProvider, ObjectMapper mapper) {
        this(tokenProvider, mapper, WechatAccessTokenProvider.defaultHttpClient());
    }

    WechatPhoneClient(WechatAccessTokenProvider tokenProvider, ObjectMapper mapper, HttpClient http) {
        this.tokenProvider = tokenProvider;
        this.mapper = mapper;
        this.http = http;
    }

    /** Internal verified result only; never return this record directly from a controller. */
    public record Phone(String appId, String mobile) {
        @Override public String toString() { return "Phone[redacted]"; }
    }

    public Phone exchange(String code, WechatConfigService.LoginCredentials current) {
        if (code == null || code.isBlank() || code.length() > 256) throw invalidAuthorization();
        var token = tokenProvider.get(current);
        for (int attempt = 0; attempt < 2; attempt++) {
            JsonNode body = request(code, token);
            int error = WechatAccessTokenProvider.errorCode(body, true);
            if (error == 40001 || error == 40014 || error == 42001) {
                if (attempt == 0) {
                    token = tokenProvider.refreshRejected(current, token);
                    continue;
                }
                tokenProvider.invalidate(current, token);
                throw invalidResponse();
            }
            // A phone authorization code is independent of the platform login session: never business 401.
            if (error == 40029 || error == 40163 || error == 41008 || error == 41024) throw invalidAuthorization();
            if (error == 48001) throw BizException.of(503, "微信手机号服务权限未开通，请联系管理员");
            if (error == 45009 || error == 45011) throw BizException.of(429, "微信手机号服务调用额度已达上限或请求过于频繁，请稍后重试");
            if (error != 0) throw invalidResponse();
            JsonNode phone = body.path("phone_info");
            JsonNode mobile = phone.path("purePhoneNumber");
            JsonNode country = phone.path("countryCode");
            JsonNode appId = phone.path("watermark").path("appid");
            if (!phone.isObject() || !mobile.isTextual() || !mobile.textValue().matches("^1[3-9][0-9]{9}$")
                    || !country.isTextual() || !"86".equals(country.textValue())
                    || !appId.isTextual() || !current.appId().equals(appId.textValue())) {
                throw invalidResponse();
            }
            return new Phone(current.appId(), mobile.textValue());
        }
        throw invalidResponse();
    }

    private JsonNode request(String code, WechatAccessTokenProvider.AccessToken token) {
        try {
            // Only this fixed HTTPS host is allowed; the access token is server-owned, never caller-provided.
            URI uri = UriComponentsBuilder.fromHttpUrl("https://api.weixin.qq.com/wxa/business/getuserphonenumber")
                    .queryParam("access_token", "{token}").encode().buildAndExpand(token.value()).toUri();
            String payload = mapper.writeValueAsString(Map.of("code", code));
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw invalidResponse();
            return WechatAccessTokenProvider.readObject(mapper, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.of(502, "微信手机号请求中断，请重试");
        } catch (BizException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // URI/code/token/phone/raw response and original exception messages must not escape.
            throw invalidResponse();
        }
    }

    private static BizException invalidAuthorization() {
        return BizException.of(400, "手机号授权凭证无效或已失效，请重新授权");
    }

    private static BizException invalidResponse() {
        return BizException.of(502, "微信手机号服务暂不可用，请重新授权或稍后重试");
    }
}
