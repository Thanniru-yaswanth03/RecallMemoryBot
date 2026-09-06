package com.recallbot.admin.security;

import com.recallbot.config.properties.RecallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * IP-based rate limiter protecting admin login against brute-force attacks.
 */
@Component
public class AdminLoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AdminLoginRateLimiter.class);

    private final RecallProperties properties;
    private final ConcurrentMap<String, AttemptTracker> attempts = new ConcurrentHashMap<>();

    public AdminLoginRateLimiter(RecallProperties properties) {
        this.properties = properties;
    }

    public boolean isBlocked(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return false;
        }

        AttemptTracker tracker = attempts.get(clientIp);
        if (tracker == null) {
            return false;
        }

        Instant now = Instant.now();
        if (tracker.lockedUntil != null) {
            if (now.isBefore(tracker.lockedUntil)) {
                return true;
            }
            // Lockout expired, clear tracker
            attempts.remove(clientIp);
            return false;
        }

        return false;
    }

    public void recordFailedAttempt(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return;
        }

        int maxAttempts = (properties != null && properties.admin() != null)
                ? properties.admin().maxLoginAttempts()
                : 5;
        int lockoutMinutes = (properties != null && properties.admin() != null)
                ? properties.admin().lockoutMinutes()
                : 15;

        attempts.compute(clientIp, (ip, tracker) -> {
            Instant now = Instant.now();
            if (tracker == null || now.isAfter(tracker.windowStart.plus(Duration.ofMinutes(lockoutMinutes)))) {
                return new AttemptTracker(1, now, null);
            }

            int newCount = tracker.failureCount + 1;
            Instant lockedUntil = tracker.lockedUntil;
            if (newCount >= maxAttempts) {
                lockedUntil = now.plus(Duration.ofMinutes(lockoutMinutes));
                log.warn("Admin login brute-force detected from IP {}. Locked out until {}", ip, lockedUntil);
            }
            return new AttemptTracker(newCount, tracker.windowStart, lockedUntil);
        });
    }

    public void recordSuccessfulLogin(String clientIp) {
        if (clientIp != null) {
            attempts.remove(clientIp);
        }
    }

    private static class AttemptTracker {
        final int failureCount;
        final Instant windowStart;
        final Instant lockedUntil;

        AttemptTracker(int failureCount, Instant windowStart, Instant lockedUntil) {
            this.failureCount = failureCount;
            this.windowStart = windowStart;
            this.lockedUntil = lockedUntil;
        }
    }
}
