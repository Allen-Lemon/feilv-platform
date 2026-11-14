package com.feilv.controller;

import com.feilv.domain.User;
import com.feilv.interceptor.AuthInterceptor;
import com.feilv.repository.UserRepository;
import com.feilv.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountStatusInterceptorTest {
    @Test void freezeRevokesExistingTokenAndUnfreezeRestoresValidRole() throws Exception {
        var users = mock(UserRepository.class);
        User user = new User(); user.setId(42L);
        when(users.findById(42L)).thenReturn(Optional.of(user));
        var interceptor = new AuthInterceptor(users);
        var req = new MockHttpServletRequest("GET", "/api/v1/user/me");
        req.addHeader("Authorization", "Bearer " + JwtUtil.create(42L, "USER"));
        try {
            assertTrue(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()));
            user.setStatus(0);
            var response = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(req, response, new Object()));
            assertEquals(403, response.getStatus());
            user.setStatus(1);
            assertTrue(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()));
            user.setRole("ADMIN");
            assertFalse(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()));
            when(users.findById(42L)).thenReturn(Optional.empty());
            assertFalse(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()));
        } finally {
            interceptor.afterCompletion(req, new MockHttpServletResponse(), new Object(), null);
        }
    }
}
