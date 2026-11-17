package com.feilv.controller;

import com.feilv.domain.User;
import com.feilv.interceptor.AuthInterceptor;
import com.feilv.repository.RiderRepository;
import com.feilv.repository.UserRepository;
import com.feilv.service.AuthTokenService;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Explicit test-only signing key and database fixtures; production has no fixture fallback. */
public final class TestAuth {
    public static final String TEST_SECRET = "feilv-test-only-jwt-secret-at-least-32-bytes";
    public static final String TEST_SECRET_PROPERTY = "security.jwt-secret=" + TEST_SECRET;
    public static final String USER_APP_ID = "wx-test-user";
    public static final String RIDER_APP_ID = "wx-test-rider";

    private TestAuth() {}

    public static AuthTokenService tokens() { return new AuthTokenService(TEST_SECRET); }
    public static String userToken(long id, String role) { return tokens().create(id, role, "USER", USER_APP_ID); }
    public static String riderToken(long id, String role) { return tokens().create(id, role, "RIDER", RIDER_APP_ID); }
    public static String adminToken(long id) { return tokens().createAdmin(id); }
    public static String userBearer(User user) { return "Bearer " + userToken(user.getId(), user.getRole()); }
    public static String riderBearer(User user) { return "Bearer " + riderToken(user.getId(), user.getRole()); }
    public static String bearer(User user) {
        return "ADMIN".equals(user.getRole()) ? "Bearer " + adminToken(user.getId()) : userBearer(user);
    }

    public static AuthInterceptor interceptor() {
        var users = mock(UserRepository.class);
        when(users.findById(anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0);
            User user = new User(); user.setId(id); user.setRole(id == 1 ? "ADMIN" : "USER");
            return Optional.of(user);
        });
        return new AuthInterceptor(tokens(), users, mock(RiderRepository.class));
    }
}
