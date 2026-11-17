package com.feilv.controller;

import com.feilv.common.UserContext;
import com.feilv.domain.Rider;
import com.feilv.domain.User;
import com.feilv.interceptor.AuthInterceptor;
import com.feilv.repository.RiderRepository;
import com.feilv.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class AuthInterceptorTest {
    private final UserRepository users = mock(UserRepository.class);
    private final RiderRepository riders = mock(RiderRepository.class);
    private final AuthInterceptor interceptor = new AuthInterceptor(TestAuth.tokens(), users, riders);
    private User account;
    private Rider rider;

    @BeforeEach void setup() {
        account = new User(); account.setId(42L); account.setRole("RIDER");
        rider = new Rider(); rider.setId(7L); rider.setUserId(42L); rider.setName("test rider");
        when(users.findById(42L)).thenReturn(Optional.of(account));
        when(riders.findByUserId(42L)).thenReturn(Optional.of(rider));
    }
    @AfterEach void clear() { UserContext.clear(); }

    @Test void sourceIsSignedAndIndependentFromBusinessRole() throws Exception {
        String consumer = TestAuth.userToken(42L, "RIDER"), riderToken = TestAuth.riderToken(42L, "RIDER");
        for (String path : new String[]{"/api/v1/orders", "/api/v1/orders/12", "/api/v1/orders/12/custom-task",
                "/api/v1/orders/12/track", "/api/v1/task-tags", "/api/v1/user/address"}) {
            assertAllowed("GET", path, consumer);
            assertRejected("GET", path, riderToken, 403, 403);
        }
        for (String path : new String[]{"/api/v1/orders", "/api/v1/orders/quote", "/api/v1/orders/12/pay",
                "/api/v1/orders/12/cancel", "/api/v1/orders/12/confirm", "/api/v1/custom-tasks",
                "/api/v1/reviews", "/api/v1/complaints"}) {
            assertAllowed("POST", path, consumer);
            assertRejected("POST", path, riderToken, 403, 403);
        }
        for (String path : new String[]{"/api/v1/rider/me", "/api/v1/rider/orders", "/api/v1/rider/orders/12",
                "/api/v1/rider/orders/12/custom-task", "/api/v1/grab/list", "/api/v1/credit/logs"}) {
            assertAllowed("GET", path, riderToken);
            assertRejected("GET", path, consumer, 403, 403);
        }
        for (String path : new String[]{"/api/v1/rider/online", "/api/v1/grab/12", "/api/v1/orders/12/grab",
                "/api/v1/orders/12/pickup", "/api/v1/orders/12/depart", "/api/v1/orders/12/deliver",
                "/api/v1/orders/12/service-start", "/api/v1/orders/12/service-complete", "/api/v1/track/report",
                "/api/v1/rider/orders/12/transfers", "/api/v1/rider/transfers/12/respond"}) {
            assertAllowed("POST", path, riderToken);
            assertRejected("POST", path, consumer, 403, 403);
        }
    }

    @Test void sharedCapabilitiesAllowEitherSourceButNeverAdmin() throws Exception {
        for (String source : new String[]{"USER", "RIDER"}) {
            String token = TestAuth.tokens().create(42L, "RIDER", source, "wx-test");
            for (String path : new String[]{"/api/v1/user/me", "/api/v1/user/phone/options", "/api/v1/wallet",
                    "/api/v1/wallet/entries", "/api/v1/orders/12/transfers"}) assertAllowed("GET", path, token);
            assertAllowed("PUT", "/api/v1/user/profile", token);
            for (String path : new String[]{"/api/v1/user/avatar", "/api/v1/user/wechat-phone",
                    "/api/v1/user/phone/change", "/api/v1/user/miniapp-link-codes", "/api/v1/wallet/withdrawals"}) {
                assertAllowed("POST", path, token);
            }
        }
        account.setRole("ADMIN");
        for (String path : new String[]{"/api/v1/user/me", "/api/v1/wallet", "/api/v1/orders/12/transfers"}) {
            assertRejected("GET", path, TestAuth.adminToken(42L), 403, 403);
        }
    }

    @Test void realtimeRiderRoleAndActiveProfileAreRequiredExceptProtectedEnrollment() throws Exception {
        account.setRole("USER");
        String applicant = TestAuth.riderToken(42L, "USER");
        assertAllowed("POST", "/api/v1/rider/enrollment", applicant);
        assertRejected("POST", "/api/v1/rider/enrollment", TestAuth.userToken(42L, "USER"), 403, 403);
        assertRejected("GET", "/api/v1/rider/me", applicant, 403, 403);
        rider.setStatus("FROZEN");
        assertRejected("POST", "/api/v1/rider/enrollment", applicant, 403, 403);
        account.setRole("RIDER");
        String token = TestAuth.riderToken(42L, "RIDER");
        assertRejected("POST", "/api/v1/orders/12/pickup", token, 403, 403);
        rider.setStatus("ACTIVE"); when(riders.findByUserId(42L)).thenReturn(Optional.empty());
        assertRejected("GET", "/api/v1/rider/orders/12", token, 403, 403);
        assertAllowed("GET", "/api/v1/wallet", token);
    }

    @Test void staleRoleAndMissingAccountExpireButFreezeRemainsBusinessForbidden() throws Exception {
        String stale = TestAuth.userToken(42L, "USER");
        assertRejected("GET", "/api/v1/user/me", stale, 401, 40101);
        account.setStatus(0);
        assertRejected("GET", "/api/v1/user/me", TestAuth.userToken(42L, "RIDER"), 403, 403);
        when(users.findById(42L)).thenReturn(Optional.empty());
        assertRejected("GET", "/api/v1/user/me", stale, 401, 40101);
        assertRejected("GET", "/api/v1/user/me", "broken", 401, 40101);
    }

    @Test void audienceLessAdminIsRestrictedToAdminRoutesIncludingExistingAdminProfile() throws Exception {
        account.setRole("ADMIN");
        String token = TestAuth.adminToken(42L);
        assertAllowed("GET", "/api/v1/admin/users", token);
        assertAllowed("POST", "/api/v1/auth/admin-profile", token);
        assertRejected("GET", "/api/v1/orders/12", token, 403, 403);
        assertRejected("POST", "/api/v1/user/miniapp-link-codes", token, 403, 403);
        account.setRole("USER");
        assertRejected("POST", "/api/v1/auth/admin-profile", TestAuth.userToken(42L, "USER"), 403, 403);
        assertRejected("POST", "/api/v1/auth/admin-profile", null, 401, 40101);
    }

    @Test void onlyExplicitAuthRoutesArePublicAndProtectedRoutesHaveNoFixtureOrHeaderFallback() throws Exception {
        for (String client : new String[]{"USER", "RIDER"}) {
            for (String action : new String[]{"login", "accounts", "identity-link"}) {
                assertAllowed("POST", "/api/v1/auth/miniapps/" + client + "/" + action, null);
            }
            assertAllowed("GET", "/api/v1/auth/miniapps/" + client + "/options", null);
        }
        assertRejected("POST", "/api/v1/auth/miniapps/OTHER/login", null, 401, 40101);
        assertRejected("POST", "/api/v1/rider/enrollment", null, 401, 40101);
        assertRejected("POST", "/api/v1/user/miniapp-link-codes", null, 401, 40101);
        assertRejected("POST", "/api/v1/auth/unknown", null, 401, 40101);
        var request = new MockHttpServletRequest("GET", "/api/v1/user/me");
        request.addHeader("X-Client", "RIDER"); request.addHeader("X-User-Id", "42");
        UserContext.set(new UserContext.CurrentUser(42L, "RIDER"));
        var response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus()); assertNull(UserContext.get());
        verify(users, never()).findById(anyLong());
    }

    @Test void acceptedContextCarriesOnlyVerifiedClaimsAndCompletionClearsIt() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/user/me");
        request.addHeader("Authorization", "Bearer " + TestAuth.riderToken(42L, "RIDER"));
        request.addHeader("X-Client", "USER");
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals("RIDER", UserContext.get().source());
        assertEquals(TestAuth.RIDER_APP_ID, UserContext.get().appId());
        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        assertNull(UserContext.get());
    }

    @Test void noncanonicalPathsAreBadRequestsBeforePublicOptionsApiOrIdentityChecks() throws Exception {
        String[] invalidPaths = {"/api;x=1/v1/admin/stats", "/%61pi/v1/admin/stats",
                "/api/v1/%72ider/online", "/api/v1/rider%3bonline", "/api/v1/rider%253bonline",
                "/api/v1/auth/wechat-login;x=1", "/api/v1/auth/miniapps/USER/options%2f",
                "/api//v1/admin/stats", "//api/v1/admin/stats", "/api/v1/./rider/online",
                "/api/v1/rider/../admin/stats", "/api/v1/rider/.", "/api/v1/rider/..",
                "/api/" + (char) 92 + "v1/admin/stats", "/health;x=1"};
        for (String method : new String[]{"GET", "POST", "OPTIONS"}) {
            for (String path : invalidPaths) {
                UserContext.set(new UserContext.CurrentUser(42L, "RIDER"));
                var response = new MockHttpServletResponse();
                assertFalse(interceptor.preHandle(request(method, path, TestAuth.userToken(42L, "RIDER")),
                        response, new Object()), method + " " + path);
                assertEquals(400, response.getStatus(), method + " " + path);
                assertTrue(response.getContentAsString().contains("\"code\":400"), path);
                assertEquals("no-store", response.getHeader("Cache-Control"));
                assertNull(UserContext.get(), path);
            }
        }
        verifyNoInteractions(users, riders);
    }

    @Test void contextPathRemovalRequiresAnExactPrefixBoundary() throws Exception {
        var wrongPrefix = request("GET", "/platform-other/api/v1/admin/stats", null);
        wrongPrefix.setContextPath("/platform");
        var response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(wrongPrefix, response, new Object()));
        assertEquals(400, response.getStatus());
        verifyNoInteractions(users, riders);
    }

    private void assertAllowed(String method, String path, String token) throws Exception {
        assertTrue(interceptor.preHandle(request(method, path, token), new MockHttpServletResponse(), new Object()), method + " " + path);
        UserContext.clear();
    }
    private void assertRejected(String method, String path, String token, int status, int code) throws Exception {
        var response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request(method, path, token), response, new Object()), method + " " + path);
        assertEquals(status, response.getStatus(), path);
        assertTrue(response.getContentAsString().contains("\"code\":" + code), path);
        assertNull(UserContext.get());
    }
    private MockHttpServletRequest request(String method, String path, String token) {
        var request = new MockHttpServletRequest(method, path);
        if (token != null) request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
