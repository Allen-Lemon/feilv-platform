package com.feilv.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/** Servlet-only checks: no application context, database, network or token fixture. */
class AuthResponseCacheFilterTest {
    private static final String[] SENSITIVE = {
            "/api/v1/auth/miniapps/USER/login", "/api/v1/auth/miniapps/RIDER/accounts",
            "/api/v1/auth/miniapps/USER/identity-link", "/api/v1/auth/miniapps/RIDER/options",
            "/api/v1/auth/wechat-login", "/api/v1/auth/admin-login", "/api/v1/auth/admin-profile",
            "/api/v1/user/miniapp-link-codes", "/api/v1/rider/enrollment"
    };

    @ParameterizedTest @ValueSource(strings = {"", "/platform"})
    void successfulSensitiveResponsesRemainUncachedWithEitherContextPath(String contextPath) throws Exception {
        for (String path : SENSITIVE) {
            MockHttpServletRequest request = request(contextPath, contextPath + path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            new AuthResponseCacheFilter().doFilter(request, response, (req, resp) -> {
                assertSame(request, req); assertSame(response, resp);
                assertUncached(response);
                response.setStatus(200);
                response.getWriter().write("{\"code\":0}");
            });
            assertEquals(200, response.getStatus()); assertUncached(response);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", "/platform"})
    void chainGeneratedAuthenticationAndBusinessErrorsKeepNoStore(String contextPath) throws Exception {
        for (String path : SENSITIVE) {
            for (int error : new int[]{400, 401, 403, 409, 500}) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                new AuthResponseCacheFilter().doFilter(request(contextPath, contextPath + path), response, (req, resp) -> {
                    assertUncached(response);
                    response.setStatus(error);
                    response.getWriter().write("{\"code\":" + error + "}");
                });
                assertEquals(error, response.getStatus()); assertUncached(response);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", "/platform"})
    void propagatedChainExceptionAlreadyHasCacheProtection(String contextPath) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletException failure = new ServletException("simulated handler failure");
        ServletException actual = assertThrows(ServletException.class, () ->
                new AuthResponseCacheFilter().doFilter(request(contextPath, contextPath + "/api/v1/rider/enrollment"), response,
                        (req, resp) -> { assertUncached(response); throw failure; }));
        assertSame(failure, actual); assertUncached(response);
    }

    @ParameterizedTest @ValueSource(strings = {"", "/platform"})
    void ordinaryPathsKeepTheirExistingCachePolicy(String contextPath) throws Exception {
        for (String path : new String[]{"/api/v1/avatars/avatar.png", "/api/v1/orders", "/health", "/api/v1/user/miniapp-link-codes-extra"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setHeader("Cache-Control", "private, max-age=60");
            new AuthResponseCacheFilter().doFilter(request(contextPath, contextPath + path), response, (req, resp) -> {
                response.setStatus(200);
            });
            assertEquals("private, max-age=60", response.getHeader("Cache-Control"));
            assertNull(response.getHeader("Pragma"));
        }
    }

    @Test void contextPathIsStrippedOnlyAtACompleteMatchingBoundary() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new AuthResponseCacheFilter().doFilter(request("/platform", "/platform-extra/api/v1/auth/wechat-login"), response,
                (req, resp) -> {});
        assertNull(response.getHeader("Cache-Control")); assertNull(response.getHeader("Pragma"));
    }

    private static MockHttpServletRequest request(String contextPath, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContextPath(contextPath);
        return request;
    }
    private static void assertUncached(MockHttpServletResponse response) {
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("no-cache", response.getHeader("Pragma"));
    }
}
