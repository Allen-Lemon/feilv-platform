package com.feilv;

import com.feilv.util.JwtUtil;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {
    private static final String SECRET = "jwt-unit-test-secret-at-least-32-bytes";
    private static final String OTHER_SECRET = "jwt-other-test-secret-at-least-32-bytes";
    private static final long FUTURE = System.currentTimeMillis() + 60_000;

    @Test
    void explicitKeyRoundTripsBothApplicationsAndAdmin() {
        var user = JwtUtil.parse(JwtUtil.create(17, "RIDER", "USER", "wx-user", SECRET), SECRET);
        assertNotNull(user);
        assertEquals(17, user.id);
        assertEquals("RIDER", user.role);
        assertEquals("USER", user.aud);
        assertEquals("wx-user", user.appId);
        assertTrue(user.exp > System.currentTimeMillis());
        var applicant = JwtUtil.parse(JwtUtil.create(17, "USER", "RIDER", "wx-rider", SECRET), SECRET);
        assertNotNull(applicant);
        assertEquals("RIDER", applicant.aud);
        var admin = JwtUtil.parse(JwtUtil.createAdmin(9, SECRET), SECRET);
        assertNotNull(admin);
        assertNull(admin.aud);
        assertNull(admin.appId);
        assertNull(JwtUtil.parse(JwtUtil.createAdmin(9, SECRET), OTHER_SECRET));
    }

    @Test
    void jacksonReadsReorderedClaimsAndRejectsDuplicatesAndTrailingJson() throws Exception {
        assertNotNull(parsePayload("{\"appId\":\"wx-user\",\"exp\":" + FUTURE
                + ",\"aud\":\"USER\",\"role\":\"USER\",\"id\":17}"));
        assertNull(parsePayload(payload("17", "USER", "USER", "wx-user", FUTURE)
                .replace("\"id\":17", "\"id\":17,\"id\":18")));
        assertNull(parsePayload(payload("17", "USER", "USER", "wx-user", FUTURE) + " {}"));
    }

    @Test
    void rejectsLegacyConsumerAndInvalidSourcesRolesIdsAndExpiry() throws Exception {
        assertNull(parsePayload("{\"id\":17,\"role\":\"USER\",\"exp\":" + FUTURE + "}"));
        assertNotNull(parsePayload("{\"id\":17,\"role\":\"ADMIN\",\"exp\":" + FUTURE + "}"));
        assertNull(parsePayload(payload("17", "ADMIN", "USER", "wx-user", FUTURE)));
        assertNull(parsePayload(payload("17", "ROOT", "USER", "wx-user", FUTURE)));
        assertNull(parsePayload(payload("17", "USER", "rider", "wx-user", FUTURE)));
        assertNull(parsePayload(payload("17", "USER", "USER", "", FUTURE)));
        assertNull(parsePayload(payload("17", "USER", "USER", "wx user", FUTURE)));
        assertNull(parsePayload(payload("0", "USER", "USER", "wx-user", FUTURE)));
        assertNull(parsePayload(payload("-17", "USER", "USER", "wx-user", FUTURE)));
        assertNull(parsePayload(payload("17.1", "USER", "USER", "wx-user", FUTURE)));
        assertNull(parsePayload(payload("9223372036854775808", "USER", "USER", "wx-user", FUTURE)));
        assertNull(parsePayload(payload("17", "USER", "USER", "wx-user", 1)));
        assertNull(parsePayload("{\"id\":17,\"role\":\"ADMIN\",\"exp\":" + FUTURE + ",\"aud\":null}"));
        assertNull(parsePayload("{\"id\":17,\"role\":\"USER\",\"exp\":" + FUTURE
                + ",\"aud\":[\"USER\"],\"appId\":\"wx-user\"}"));
        assertNull(parsePayload("{\"id\":17,\"role\":\"USER\",\"exp\":\"" + FUTURE
                + "\",\"aud\":\"USER\",\"appId\":\"wx-user\"}"));
    }

    @Test
    void malformedTokensAndHeadersReturnNullInsteadOfThrowing() throws Exception {
        for (String token : new String[]{null, "", "a.b.c", "!.!.!", "a.b.c.d", "a..", ".a.a", "x".repeat(8193)}) {
            assertNull(assertDoesNotThrow(() -> JwtUtil.parse(token, SECRET)));
        }
        String body = payload("17", "USER", "USER", "wx-user", FUTURE);
        assertNull(JwtUtil.parse(sign("{\"alg\":\"none\",\"typ\":\"JWT\"}", body), SECRET));
        assertNull(JwtUtil.parse(sign("{\"alg\":\"HS512\",\"typ\":\"JWT\"}", body), SECRET));
        assertNull(JwtUtil.parse(sign("{\"alg\":\"HS256\",\"typ\":\"OTHER\"}", body), SECRET));
        assertNull(JwtUtil.parse(sign("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"crit\":[\"custom\"]}", body), SECRET));
        assertNull(JwtUtil.parse(sign("{\"alg\":\"HS256\",\"alg\":\"none\",\"typ\":\"JWT\"}", body), SECRET));
        assertNull(JwtUtil.parse(sign("{\"alg\":\"HS256\",\"typ\":\"JWT\"} {}", body), SECRET));
        assertNull(parsePayload("not-json"));
        assertNull(parsePayload("[]"));
        String valid = JwtUtil.create(17, "USER", "USER", "wx-user", SECRET);
        assertNull(JwtUtil.parse(valid.substring(0, valid.length() - 5) + "xxxxx", SECRET));
    }

    @Test
    void keysAndIssuedClaimsFailClosed() {
        for (String secret : new String[]{null, "", " ".repeat(40), "short"}) {
            assertThrows(IllegalArgumentException.class, () -> JwtUtil.parse("token", secret));
            assertThrows(IllegalArgumentException.class, () -> JwtUtil.createAdmin(1, secret));
        }
        assertThrows(IllegalArgumentException.class, () -> JwtUtil.create(1, "USER", null, null, SECRET));
        assertThrows(IllegalArgumentException.class, () -> JwtUtil.create(1, "ADMIN", "RIDER", "wx-rider", SECRET));
        assertThrows(IllegalArgumentException.class, () -> JwtUtil.createAdmin(0, SECRET));
    }

    private static JwtUtil.Claims parsePayload(String payload) throws Exception {
        return JwtUtil.parse(sign("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", payload), SECRET);
    }

    private static String payload(String id, String role, String aud, String appId, long exp) {
        return "{\"id\":" + id + ",\"role\":\"" + role + "\",\"aud\":\"" + aud
                + "\",\"appId\":\"" + appId + "\",\"exp\":" + exp + "}";
    }

    private static String sign(String header, String payload) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String data = encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return data + "." + encoder.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.US_ASCII)));
    }
}
