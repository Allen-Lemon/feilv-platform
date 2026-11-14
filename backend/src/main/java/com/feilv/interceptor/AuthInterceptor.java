package com.feilv.interceptor;

import com.feilv.common.UserContext;
import com.feilv.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器：
 * 1) /api/v1/auth/** 与 OPTIONS 放行；
 * 2) /api/v1/admin/** 须 ADMIN 角色；
 * 3) 头像公开GET仅返回服务端重新编码的PNG；其余 /api/** 校验登录态和数据库实时账户状态。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final com.feilv.repository.UserRepository users;

    public AuthInterceptor(com.feilv.repository.UserRepository users) {
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        String method = req.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) return true;

        String uri = req.getRequestURI();
        if (uri.equals("/api/v1/auth/sms-code")
                || uri.equals("/api/v1/auth/login")
                || uri.equals("/api/v1/auth/wechat-login")
                || uri.equals("/api/v1/auth/rider-register")
                || uri.equals("/api/v1/auth/admin-login")) return true;
        if ("GET".equalsIgnoreCase(method) && uri.matches("^/api/v1/avatars/[a-f0-9-]{36}\\.png$")) return true;
        if (!uri.startsWith("/api/")) return true;

        String token = req.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) token = token.substring(7);
        JwtUtil.Claims c = JwtUtil.parse(token);
        if (c == null) {
            resp.setStatus(401);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"code\":401,\"msg\":\"登录已失效，请重新登录\"}");
            return false;
        }
        var account = users.findById(c.id).orElse(null);
        if (account == null || account.getStatus() != 1 || !c.role.equals(account.getRole())) {
            resp.setStatus(403);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"code\":403,\"msg\":\"账户不可用，请重新登录或联系客服\"}");
            return false;
        }
        if (uri.startsWith("/api/v1/admin/") && !"ADMIN".equals(c.role)) {
            resp.setStatus(403);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"code\":403,\"msg\":\"无管理后台权限\"}");
            return false;
        }
        UserContext.set(new UserContext.CurrentUser(c.id, c.role));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        UserContext.clear(); // 防止线程复用串号
    }
}
