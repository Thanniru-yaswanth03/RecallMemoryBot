package com.recallbot.admin.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages active in-memory administrator sessions with cryptographically secure tokens.
 */
@Component
public class AdminSessionManager {

    private static final Logger log = LoggerFactory.getLogger(AdminSessionManager.class);
    private static final int TOKEN_BYTE_LENGTH = 32; // 256 bits

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentMap<String, AdminSession> activeSessions = new ConcurrentHashMap<>();

    /**
     * Creates and stores a new administrator session.
     */
    public AdminSession createSession(String username, Duration ttl) {
        byte[] tokenBytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);

        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);

        AdminSession session = new AdminSession(token, username, now, expiresAt);
        activeSessions.put(token, session);
        log.info("Created admin session for user '{}' with TTL {}", username, ttl);
        return session;
    }

    /**
     * Validates an active session token, renewing its last access time if valid.
     */
    public Optional<AdminSession> validateSession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        AdminSession session = activeSessions.get(token);
        if (session == null) {
            return Optional.empty();
        }

        if (session.isExpired()) {
            activeSessions.remove(token);
            log.debug("Evicted expired admin session token");
            return Optional.empty();
        }

        session.setLastAccessAt(Instant.now());
        return Optional.of(session);
    }

    /**
     * Explicitly invalidates a session token (logout).
     */
    public void invalidateSession(String token) {
        if (token != null) {
            AdminSession removed = activeSessions.remove(token);
            if (removed != null) {
                log.info("Invalidated admin session for user '{}'", removed.getUsername());
            }
        }
    }

    /**
     * Periodically cleans up expired sessions every 10 minutes.
     */
    @Scheduled(fixedDelay = 600000)
    public void evictExpiredSessions() {
        int removedCount = 0;
        for (var entry : activeSessions.entrySet()) {
            if (entry.getValue().isExpired()) {
                activeSessions.remove(entry.getKey());
                removedCount++;
            }
        }
        if (removedCount > 0) {
            log.info("Evicted {} expired admin sessions", removedCount);
        }
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }
}
