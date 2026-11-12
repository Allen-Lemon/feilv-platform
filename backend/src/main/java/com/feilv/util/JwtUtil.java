package com.feilv.util;

import com.feilv.common.BizException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 轻量 JWT（HS256）实现，不引入第三方依赖。
 * Payload: {"id":用户ID,"role":"USER","exp":过期毫秒}
 * 生产可替换为 jjwt/sa-token；密钥须改为配置中心下发。
 */
public class JwtUtil {

    private static final String SECRET = "feilv-jwt-secret-2026-dejiang-128bit-min";
    private static final long TTL_MS = 7L * 24 * 3600 * 1000; // 7天

    public static class Claims {
        public long id; public String role; public long exp;
    }

    public static String create(Long userId, String role) {
        long exp = System.currentTimeMillis() + TTL_MS;
        String h = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String p = b64("{\"id\":" + userId + ",\"role\":\"" + role + "\",\"exp\":" + exp + "}");
        return h + "." + p + "." + hmac(h + "." + p);
    }

    public static Claims parse(String token) {
        if (token == null || token.isBlank()) return null;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        if (!hmac(parts[0] + "." + parts[1]).equals(parts[2])) return null; // 验签防伪造
        Claims c = new Claims();
        String json = deb64(parts[1]);
        try {
            c.id = Long.parseLong(extract(json, "\"id\":", ","));
            c.role = extract(json, "\"role\":\"", "\"");
            c.exp = Long.parseLong(extract(json, "\"exp\":", "}"));
        } catch (Exception e) {
            return null;
        }
        if (c.exp < System.currentTimeMillis()) return null; // 过期
        return c;
    }

    private static String extract(String json, String start, String end) {
        int i = json.indexOf(start) + start.length();
        return json.substring(i, json.indexOf(end, i));
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String deb64(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

    private static String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw BizException.of(500, "令牌生成失败");
        }
    }
}
