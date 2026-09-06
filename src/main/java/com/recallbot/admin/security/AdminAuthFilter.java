package com.recallbot.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Filter enforcing authentication for administrative REST APIs and web views.
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);

    public static final String SESSION_COOKIE_NAME = "RECALL_ADMIN_SESSION";
    public static final String AUTH_HEADER = "Authorization";
    public static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";
    public static final String BEARER_PREFIX = "Bearer ";

    private final AdminSessionManager sessionManager;

    public AdminAuthFilter(AdminSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        String normalizedPath = path.toLowerCase();

        // Only filter /api/admin and /admin paths
        if (!normalizedPath.startsWith("/api/admin") && !normalizedPath.startsWith("/admin")) {
            return true;
        }

        // Whitelisted public admin endpoints
        if (normalizedPath.equals("/api/admin/auth/login") ||
            normalizedPath.equals("/api/admin/auth/login/") ||
            normalizedPath.equals("/admin/login.html") ||
            normalizedPath.equals("/admin/login") ||
            normalizedPath.equals("/admin/login/") ||
            normalizedPath.startsWith("/admin/css/") ||
            normalizedPath.startsWith("/admin/js/") ||
            normalizedPath.startsWith("/admin/assets/")) {
            return true;
        }

        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // Inject standard security headers on all admin responses
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");

        String token = extractToken(request);
        Optional<AdminSession> sessionOpt = sessionManager.validateSession(token);

        if (sessionOpt.isEmpty()) {
            String path = request.getRequestURI();
            String normalizedPath = path != null ? path.toLowerCase() : "";
            if (normalizedPath.startsWith("/api/admin")) {
                rejectApiUnauthorized(response, "Administrator session invalid, missing, or expired");
            } else {
                // Browser page request -> redirect to login page
                response.sendRedirect("/admin/login.html");
            }
            return;
        }

        // Valid session, proceed
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        // 1. Check Authorization: Bearer <token>
        String authHeader = request.getHeader(AUTH_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }

        // 2. Check X-Admin-Token header
        String customHeader = request.getHeader(ADMIN_TOKEN_HEADER);
        if (customHeader != null && !customHeader.isBlank()) {
            return customHeader.trim();
        }

        // 3. Check RECALL_ADMIN_SESSION Cookie
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (SESSION_COOKIE_NAME.equals(cookie.getName())) {
                    String val = cookie.getValue();
                    if (val != null && !val.isBlank()) {
                        return val.trim();
                    }
                }
            }
        }

        return null;
    }

    private void rejectApiUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"" + message + "\"}");
    }
}
