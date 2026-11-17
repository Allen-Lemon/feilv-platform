package com.feilv;

import com.feilv.common.BizException;
import com.feilv.domain.User;
import com.feilv.service.AuthTokenService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthTokenServiceTest {
    private static final String SECRET = "token-service-test-secret-at-least-32-bytes";

    @Test
    void requiresAnExplicitStrongKeyForEachInstance() {
        assertThrows(IllegalArgumentException.class, () -> new AuthTokenService(null));
        assertThrows(IllegalArgumentException.class, () -> new AuthTokenService(""));
        assertThrows(IllegalArgumentException.class, () -> new AuthTokenService("short"));
        AuthTokenService first = new AuthTokenService(SECRET);
        AuthTokenService second = new AuthTokenService("independent-test-signing-secret-at-least-32-bytes");
        String token = first.createAdmin(7);
        assertNotNull(first.parse(token));
        assertNull(second.parse(token));
        assertNotNull(first.parse(token), "constructing another service must not mutate a global signing key");
    }

    @Test
    void loginSignsServerChosenSourceAndKeepsLoginResponseContract() {
        User user = new User();
        user.setId(17L);
        user.setRole("RIDER");
        user.setNickname("rider");
        AuthTokenService service = new AuthTokenService(SECRET);
        var login = service.login(user, "USER", "wx-user");
        assertEquals(17, login.userId());
        assertEquals("RIDER", login.role());
        assertEquals("rider", login.nickname());
        var claims = service.parse(login.token());
        assertNotNull(claims);
        assertEquals("USER", claims.aud);
        assertEquals("wx-user", claims.appId);
        assertEquals("RIDER", claims.role);
    }

    @Test
    void consumerLoginRejectsAdministratorsAndUnavailableAccounts() {
        AuthTokenService service = new AuthTokenService(SECRET);
        User user = new User();
        user.setId(17L);
        user.setRole("ADMIN");
        assertEquals(403, assertThrows(BizException.class, () -> service.login(user, "USER", "wx-user")).getCode());
        user.setRole("USER");
        user.setStatus(0);
        assertEquals(403, assertThrows(BizException.class, () -> service.login(user, "RIDER", "wx-rider")).getCode());
        assertThrows(BizException.class, () -> service.login(null, "USER", "wx-user"));
    }
}
