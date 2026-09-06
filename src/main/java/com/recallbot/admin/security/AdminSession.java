package com.recallbot.admin.security;

import java.time.Instant;

/**
 * Represents an authenticated administrator session.
 */
public class AdminSession {

    private final String token;
    private final String username;
    private final Instant createdAt;
    private volatile Instant lastAccessAt;
    private volatile Instant expiresAt;

    public AdminSession(String token, String username, Instant createdAt, Instant expiresAt) {
        this.token = token;
        this.username = username;
        this.createdAt = createdAt;
        this.lastAccessAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getToken() {
        return token;
    }

    public String getUsername() {
        return username;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastAccessAt() {
        return lastAccessAt;
    }

    public void setLastAccessAt(Instant lastAccessAt) {
        this.lastAccessAt = lastAccessAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
