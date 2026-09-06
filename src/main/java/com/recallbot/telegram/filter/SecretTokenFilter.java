package com.recallbot.telegram.filter;

import com.recallbot.config.properties.RecallProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Filter verifying that incoming webhook requests carry the expected
 * X-Telegram-Bot-Api-Secret-Token header configured during webhook setup.
 * Uses constant-time comparison to prevent timing attacks.
 */
@Component
public class SecretTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SecretTokenFilter.class);
    public static final String SECRET_TOKEN_HEADER = "X-Telegram-Bot-Api-Secret-Token";
    public static final String WEBHOOK_PATH = "/api/telegram/webhook";

    private final RecallProperties properties;

    public SecretTokenFilter(RecallProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        return !path.equals(WEBHOOK_PATH) && !path.equals(WEBHOOK_PATH + "/") && !path.startsWith(WEBHOOK_PATH + "/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String configuredSecret = (properties != null && properties.telegram() != null)
                ? properties.telegram().webhookSecret()
                : null;

        // If no secret configured (e.g. placeholder in dev/polling), pass through with warning
        if (configuredSecret == null || configuredSecret.isBlank() || "placeholder_secret".equals(configuredSecret)) {
            log.warn("Telegram webhook secret is using default or unconfigured value. Skipping strict header validation.");
            filterChain.doFilter(request, response);
            return;
        }

        String receivedToken = request.getHeader(SECRET_TOKEN_HEADER);

        if (receivedToken == null || receivedToken.isBlank()) {
            log.warn("Rejected Telegram webhook request: missing {} header from IP {}",
                    SECRET_TOKEN_HEADER, request.getRemoteAddr());
            rejectUnauthorized(response, "Missing secret token header");
            return;
        }

        byte[] expectedBytes = configuredSecret.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = receivedToken.getBytes(StandardCharsets.UTF_8);

        if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
            log.warn("Rejected Telegram webhook request: invalid secret token header from IP {}",
                    request.getRemoteAddr());
            rejectUnauthorized(response, "Invalid secret token");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void rejectUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"" + message + "\"}");
    }
}
