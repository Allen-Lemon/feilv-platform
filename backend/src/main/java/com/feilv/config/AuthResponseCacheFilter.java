package com.feilv.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Includes validation and interceptor failures, not just successful credential responses. */
@Component
public class AuthResponseCacheFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty()
                && (uri.equals(contextPath) || uri.startsWith(contextPath + "/"))) {
            uri = uri.substring(contextPath.length());
        }
        return !(uri.startsWith("/api/v1/auth/") || uri.equals("/api/v1/user/miniapp-link-codes")
                || uri.equals("/api/v1/rider/enrollment"));
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        chain.doFilter(request, response);
    }
}
