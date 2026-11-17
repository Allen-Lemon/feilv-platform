package com.feilv.util;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;

/** Bounded HS256 codec. Every operation receives its key explicitly; no environment or global key. */
public final class JwtUtil {
    private static final long TTL_MS = 7L * 24 * 3600 * 1000;
    private static final int MAX_TOKEN_LENGTH = 8192;
    private static final Set<String> ROLES = Set.of("USER", "RIDER", "ADMIN");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private JwtUtil() {}

    public static final class Claims {
        public final long id;
        public final String role;
        /** Existing tokens use epoch milliseconds, not JWT epoch seconds. */
        public final long exp;
        public final String aud;
        public final String appId;

        private Claims(long id, String role, long exp, String aud, String appId) {
            this.id = id;
            this.role = role;
            this.exp = exp;
            this.aud = aud;
            this.appId = appId;
        }
    }

    public static String create(long id, String role, String audience, String appId, String secret) {
        validateSecret(secret);
        if (!validClaims(id, role, audience, appId)) {
            throw new IllegalArgumentException("Invalid token identity or application source");
        }
        ObjectNode header = JSON.createObjectNode().put("alg", "HS256").put("typ", "JWT");
        ObjectNode payload = JSON.createObjectNode().put("id", id).put("role", role)
                .put("exp", System.currentTimeMillis() + TTL_MS);
        if (audience != null) payload.put("aud", audience).put("appId", appId);
        String signed = encode(header.toString().getBytes(StandardCharsets.UTF_8)) + "."
                + encode(payload.toString().getBytes(StandardCharsets.UTF_8));
        return signed + "." + encode(hmac(signed, secret));
    }

    /** Audience-less tokens are reserved for the existing administrator login only. */
    public static String createAdmin(long id, String secret) {
        return create(id, "ADMIN", null, null, secret);
    }

    /** Malformed, expired, legacy C-end and incorrectly signed tokens are authentication failures. */
    public static Claims parse(String token, String secret) {
        validateSecret(secret);
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) return null;
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[0].length() > 1024 || parts[1].length() > 6144
                    || parts[2].length() != 43) return null;
            for (String part : parts) {
                if (part.isEmpty() || !part.matches("[A-Za-z0-9_-]+")) return null;
            }
            byte[] signature = DECODER.decode(parts[2]);
            if (!MessageDigest.isEqual(hmac(parts[0] + "." + parts[1], secret), signature)) return null;
            JsonNode header = JSON.readTree(DECODER.decode(parts[0]));
            if (header == null || !header.isObject() || !textEquals(header.get("alg"), "HS256")
                    || !textEquals(header.get("typ"), "JWT") || header.has("crit") || header.has("b64")) return null;
            JsonNode payload = JSON.readTree(DECODER.decode(parts[1]));
            if (payload == null || !payload.isObject()) return null;
            JsonNode id = payload.get("id");
            JsonNode exp = payload.get("exp");
            JsonNode role = payload.get("role");
            if (id == null || !id.isIntegralNumber() || !id.canConvertToLong()
                    || exp == null || !exp.isIntegralNumber() || !exp.canConvertToLong()
                    || role == null || !role.isTextual()) return null;
            String audience = optionalText(payload, "aud");
            String appId = optionalText(payload, "appId");
            // Explicit JSON null is not a legacy absent claim.
            if ((payload.has("aud") && audience == null) || (payload.has("appId") && appId == null)) return null;
            if (!validClaims(id.longValue(), role.textValue(), audience, appId)
                    || exp.longValue() <= System.currentTimeMillis()) return null;
            return new Claims(id.longValue(), role.textValue(), exp.longValue(), audience, appId);
        } catch (Exception malformed) {
            return null;
        }
    }

    public static void validateSecret(String secret) {
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("security.jwt-secret must contain at least 32 UTF-8 bytes");
        }
    }

    private static boolean validClaims(long id, String role, String audience, String appId) {
        if (id <= 0 || role == null || !ROLES.contains(role)) return false;
        if ("ADMIN".equals(role)) return audience == null && appId == null;
        return ("USER".equals(audience) || "RIDER".equals(audience))
                && appId != null && !appId.isBlank() && appId.length() <= 128
                && appId.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    private static boolean textEquals(JsonNode node, String value) {
        return node != null && node.isTextual() && value.equals(node.textValue());
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String encode(byte[] bytes) {
        return ENCODER.encodeToString(bytes);
    }

    private static byte[] hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HS256 is unavailable", e);
        }
    }
}
