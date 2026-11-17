package com.feilv.interceptor;

import com.feilv.common.UserContext;
import com.feilv.repository.RiderRepository;
import com.feilv.repository.UserRepository;
import com.feilv.service.AuthTokenService;
import com.feilv.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Set;

/** Signed application source and live business role are separate authorization requirements. */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    private static final Set<String> PUBLIC_AUTH_POSTS = Set.of(
            "/api/v1/auth/sms-code", "/api/v1/auth/login", "/api/v1/auth/wechat-login",
            "/api/v1/auth/rider-register", "/api/v1/auth/admin-login");
    private static final Set<String> SHARED_USER_POSTS = Set.of(
            "/api/v1/user/avatar", "/api/v1/user/wechat-phone", "/api/v1/user/wechat-phone/change",
            "/api/v1/user/phone", "/api/v1/user/phone/sms-code", "/api/v1/user/phone/development",
            "/api/v1/user/phone/change", "/api/v1/user/phone/change/sms-code",
            "/api/v1/user/phone/development/change", "/api/v1/user/miniapp-link-codes");

    private final AuthTokenService tokens;
    private final UserRepository users;
    private final RiderRepository riders;

    public AuthInterceptor(AuthTokenService tokens, UserRepository users, RiderRepository riders) {
        this.tokens = tokens;
        this.users = users;
        this.riders = riders;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        // Rejected requests and public requests must not inherit a thread's previous identity either.
        UserContext.clear();
        String uri = applicationPath(req);
        if (uri == null) return reject(resp, 400, 400, "请求路径不规范");
        String method = req.getMethod();
        if ("OPTIONS".equals(method)) return true;
        if (isPublic(method, uri) || !uri.startsWith("/api/")) return true;

        String authorization = req.getHeader("Authorization");
        JwtUtil.Claims claims = authorization != null && authorization.startsWith("Bearer ")
                ? tokens.parse(authorization.substring(7)) : null;
        if (claims == null) return expired(resp);
        var account = users.findById(claims.id).orElse(null);
        if (account == null) return expired(resp);
        if (account.getStatus() != 1) return forbidden(resp);
        // Enrollment changes the live role. The other application's stale token must trigger relogin,
        // not a generic business 403 which would strand pending/idempotent business operations.
        if (!claims.role.equals(account.getRole())) return expired(resp);

        boolean adminRoute = uri.equals("/api/v1/admin") || uri.startsWith("/api/v1/admin/")
                || uri.equals("/api/v1/auth/admin-profile");
        if (adminRoute) {
            if (!"ADMIN".equals(claims.role) || claims.aud != null || claims.appId != null) return forbidden(resp);
        } else {
            // The sole legacy aud-less exception is ADMIN on admin routes, never any C-end endpoint.
            if ("ADMIN".equals(claims.role) || claims.aud == null || claims.appId == null) return forbidden(resp);
            if ("POST".equals(method) && "/api/v1/rider/enrollment".equals(uri)) {
                if (!"RIDER".equals(claims.aud)) return forbidden(resp);
                var rider = riders.findByUserId(claims.id).orElse(null);
                if (rider != null && !"ACTIVE".equals(rider.getStatus())) return forbidden(resp);
                // USER-role applicants are intentionally allowed only on this protected route.
            } else if (isRiderOperation(method, uri)) {
                if (!"RIDER".equals(claims.aud) || !"RIDER".equals(account.getRole())) return forbidden(resp);
                var rider = riders.findByUserId(claims.id).orElse(null);
                if (rider == null || !"ACTIVE".equals(rider.getStatus())) return forbidden(resp);
            } else if (!isShared(method, uri) && !"USER".equals(claims.aud)) {
                // Consumer orders, addresses, reviews, complaints, task tags and generic detail/meta
                // remain USER-source only. Rider detail has its own assigned-rider controller.
                return forbidden(resp);
            }
        }
        UserContext.set(new UserContext.CurrentUser(claims.id, claims.role, claims.aud, claims.appId));
        return true;
    }

    /** Reject aliases rather than decoding/normalizing a path differently from the MVC matcher. */
    private static String applicationPath(HttpServletRequest request) {
        String raw = request.getRequestURI();
        if (raw == null || !raw.startsWith("/") || raw.indexOf(';') >= 0 || raw.indexOf('%') >= 0
                || raw.indexOf('\\') >= 0 || raw.contains("//")) return null;
        for (String segment : raw.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) return null;
        }
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.isEmpty()) return raw;
        if (!raw.startsWith(contextPath)) return null;
        if (raw.length() == contextPath.length()) return "/";
        if (raw.charAt(contextPath.length()) != '/') return null;
        return raw.substring(contextPath.length());
    }

    private static boolean isPublic(String method, String uri) {
        // Exact legacy auth paths have POST handlers only. Other verbs reach MVC's 405/Allow POST,
        // not a catch-all public /auth prefix or an authentication failure masking method rejection.
        if (PUBLIC_AUTH_POSTS.contains(uri)) return true;
        if ("POST".equals(method)) {
            return uri.matches("^/api/v1/auth/miniapps/(USER|RIDER)/(login|accounts|identity-link)$");
        }
        return "GET".equals(method) && (uri.matches("^/api/v1/avatars/[a-f0-9-]{36}\\.png$")
                || uri.matches("^/api/v1/auth/miniapps/(USER|RIDER)/options$"));
    }

    private static boolean isShared(String method, String uri) {
        if (uri.equals("/api/v1/wallet") || uri.startsWith("/api/v1/wallet/")) return true;
        if ("POST".equals(method)) return SHARED_USER_POSTS.contains(uri);
        if ("PUT".equals(method)) return "/api/v1/user/profile".equals(uri);
        return "GET".equals(method)
                && ("/api/v1/user/me".equals(uri) || "/api/v1/user/phone/options".equals(uri)
                    // Safe transfer history is shared; service checks owner/current/historical participation.
                    || uri.matches("^/api/v1/orders/[^/]+/transfers$"));
    }

    private static boolean isRiderOperation(String method, String uri) {
        if (uri.equals("/api/v1/rider") || uri.startsWith("/api/v1/rider/")) return true;
        if ("GET".equals(method)) {
            return "/api/v1/grab/list".equals(uri) || "/api/v1/credit/logs".equals(uri);
        }
        return "POST".equals(method) && (uri.matches("^/api/v1/grab/[^/]+$")
                || "/api/v1/track/report".equals(uri)
                || uri.matches("^/api/v1/orders/[^/]+/(grab|pickup|depart|deliver|service-start|service-complete|transfers)$"));
    }

    private static boolean expired(HttpServletResponse response) throws IOException {
        return reject(response, 401, 40101, "登录已失效，请重新登录");
    }

    private static boolean forbidden(HttpServletResponse response) throws IOException {
        return reject(response, 403, 403, "无权限执行该操作或账户不可用");
    }

    private static boolean reject(HttpServletResponse response, int status, int code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":" + code + ",\"msg\":\"" + message + "\"}");
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        UserContext.clear();
    }
}
