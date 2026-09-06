package com.recallbot.admin.controller;

import com.recallbot.admin.dto.LoginRequest;
import com.recallbot.admin.dto.LoginResponse;
import com.recallbot.admin.security.AdminAuthFilter;
import com.recallbot.admin.security.AdminLoginRateLimiter;
import com.recallbot.admin.security.AdminSession;
import com.recallbot.admin.security.AdminSessionManager;
import com.recallbot.config.properties.RecallProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthController.class);

    private final AdminSessionManager sessionManager;
    private final AdminLoginRateLimiter rateLimiter;
    private final RecallProperties properties;

    public AdminAuthController(
            AdminSessionManager sessionManager,
            AdminLoginRateLimiter rateLimiter,
            RecallProperties properties
    ) {
        this.sessionManager = sessionManager;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        String clientIp = getClientIp(httpRequest);

        if (rateLimiter.isBlocked(clientIp)) {
            log.warn("Blocked login attempt from rate-limited IP: {}", clientIp);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "too_many_requests", "message", "Too many failed login attempts. Please wait 15 minutes."));
        }

        String configuredUsername = (properties != null && properties.admin() != null)
                ? properties.admin().username()
                : "admin";
        String configuredPassword = (properties != null && properties.admin() != null)
                ? properties.admin().password()
                : "admin_recall_secret";

        byte[] expectedUser = configuredUsername.getBytes(StandardCharsets.UTF_8);
        byte[] actualUser = request.username().getBytes(StandardCharsets.UTF_8);
        byte[] expectedPass = configuredPassword.getBytes(StandardCharsets.UTF_8);
        byte[] actualPass = request.password().getBytes(StandardCharsets.UTF_8);

        boolean userMatch = MessageDigest.isEqual(expectedUser, actualUser);
        boolean passMatch = MessageDigest.isEqual(expectedPass, actualPass);

        if (!userMatch || !passMatch) {
            rateLimiter.recordFailedAttempt(clientIp);
            log.warn("Invalid admin login attempt for user '{}' from IP {}", request.username(), clientIp);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized", "message", "Invalid username or password"));
        }

        rateLimiter.recordSuccessfulLogin(clientIp);

        int ttlHours = (properties != null && properties.admin() != null)
                ? properties.admin().sessionTtlHours()
                : 12;
        AdminSession session = sessionManager.createSession(request.username(), Duration.ofHours(ttlHours));

        // Set HttpOnly, SameSite=Strict cookie
        Cookie cookie = new Cookie(AdminAuthFilter.SESSION_COOKIE_NAME, session.getToken());
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(ttlHours * 3600);
        // If request is secure or in production, set secure
        if (httpRequest.isSecure()) {
            cookie.setSecure(true);
        }
        httpResponse.addCookie(cookie);

        return ResponseEntity.ok(new LoginResponse(
                "authenticated",
                session.getToken(),
                session.getUsername(),
                session.getExpiresAt()
        ));
    }

    @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> logout(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        String token = extractToken(httpRequest);
        if (token != null) {
            sessionManager.invalidateSession(token);
        }

        // Expire cookie
        Cookie cookie = new Cookie(AdminAuthFilter.SESSION_COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        httpResponse.addCookie(cookie);

        return ResponseEntity.ok(Map.of("status", "logged_out"));
    }

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> me(HttpServletRequest httpRequest) {
        String token = extractToken(httpRequest);
        Optional<AdminSession> sessionOpt = sessionManager.validateSession(token);

        if (sessionOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized", "message", "Not authenticated"));
        }

        AdminSession session = sessionOpt.get();
        return ResponseEntity.ok(Map.of(
                "authenticated", true,
                "username", session.getUsername(),
                "expiresAt", session.getExpiresAt().toString()
        ));
    }

    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader(AdminAuthFilter.AUTH_HEADER);
        if (authHeader != null && authHeader.startsWith(AdminAuthFilter.BEARER_PREFIX)) {
            return authHeader.substring(AdminAuthFilter.BEARER_PREFIX.length()).trim();
        }

        String customHeader = request.getHeader(AdminAuthFilter.ADMIN_TOKEN_HEADER);
        if (customHeader != null && !customHeader.isBlank()) {
            return customHeader.trim();
        }

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (AdminAuthFilter.SESSION_COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
