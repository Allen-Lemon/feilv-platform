package com.feilv.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feilv.common.BizException;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class WechatSessionClient {
    private final java.util.function.Supplier<WechatConfigService.LoginCredentials> credentials;
    private final ObjectMapper mapper;
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public WechatSessionClient(WechatConfigService settings, ObjectMapper mapper) {
        this(settings::effectiveLogin, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    // 包级构造函数仅用于隔离单元测试，不用于生产注入。
    WechatSessionClient(String appId, String secret, ObjectMapper mapper) {
        this(appId, secret, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }
    WechatSessionClient(String appId, String secret, ObjectMapper mapper, HttpClient http) {
        this(() -> new WechatConfigService.LoginCredentials(appId, secret), mapper, http);
    }
    WechatSessionClient(java.util.function.Supplier<WechatConfigService.LoginCredentials> credentials, ObjectMapper mapper, HttpClient http) {
        this.credentials = credentials;
        this.mapper = mapper;
        this.http = http;
    }

    public record Session(String appId, String openid, String unionid) {}

    public Session exchange(String code) {
        var current = credentials.get();
        String appId = current.appId();
        String secret = current.secret();
        if (appId.isBlank() || secret.isBlank()) {
            throw BizException.of(503, "微信登录未配置，请联系管理员设置 WECHAT_APP_ID 和 WECHAT_APP_SECRET");
        }
        // 固定微信官方地址，不接受客户端提供 URL、openid 或 session_key。
        URI uri = UriComponentsBuilder.fromHttpUrl("https://api.weixin.qq.com/sns/jscode2session")
                .queryParam("appid", appId).queryParam("secret", secret)
                .queryParam("js_code", code).queryParam("grant_type", "authorization_code")
                .build().encode().toUri();
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw BizException.of(502, "微信服务暂不可用，请稍后再试");
            JsonNode body = mapper.readTree(response.body());
            if (body == null || !body.isObject()) throw BizException.of(502, "微信登录响应异常");
            int error = body.path("errcode").asInt(0);
            if (error == 40029 || error == 40163) throw BizException.of(401, "微信登录凭证已失效，请重新登录");
            if (error != 0) throw BizException.of(502, "微信登录失败，请稍后再试");
            String openid = body.path("openid").asText("");
            if (openid.isBlank() || openid.length() > 128) throw BizException.of(502, "微信登录响应异常");
            String unionid = body.path("unionid").asText("");
            return new Session(appId, openid, unionid.isBlank() ? null : unionid);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.of(502, "微信登录请求中断，请重试");
        } catch (IOException e) {
            // 不输出异常内容，避免 URI 内的 secret/code 出现在日志中。
            throw BizException.of(502, "连接微信服务失败，请稍后重试");
        }
    }
}
