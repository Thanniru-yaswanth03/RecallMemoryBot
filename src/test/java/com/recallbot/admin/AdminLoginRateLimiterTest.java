package com.recallbot.admin;

import com.recallbot.admin.security.AdminLoginRateLimiter;
import com.recallbot.config.properties.RecallProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminLoginRateLimiterTest {

    private AdminLoginRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        RecallProperties.Admin adminConfig = new RecallProperties.Admin(true, "admin", "secret", 12, 3, 10);
        RecallProperties properties = new RecallProperties(null, null, null, null, adminConfig);
        rateLimiter = new AdminLoginRateLimiter(properties);
    }

    @Test
    @DisplayName("isBlocked returns false initially for any IP")
    void initiallyNotBlocked() {
        assertThat(rateLimiter.isBlocked("192.168.1.100")).isFalse();
    }

    @Test
    @DisplayName("blocks IP after exceeding maximum failed attempts")
    void blocksIpAfterExceedingThreshold() {
        String ip = "10.0.0.5";

        rateLimiter.recordFailedAttempt(ip);
        assertThat(rateLimiter.isBlocked(ip)).isFalse();

        rateLimiter.recordFailedAttempt(ip);
        assertThat(rateLimiter.isBlocked(ip)).isFalse();

        // 3rd attempt exceeds threshold (maxLoginAttempts = 3)
        rateLimiter.recordFailedAttempt(ip);
        assertThat(rateLimiter.isBlocked(ip)).isTrue();
    }

    @Test
    @DisplayName("successful login resets failed attempts")
    void successfulLoginResetsAttempts() {
        String ip = "10.0.0.6";

        rateLimiter.recordFailedAttempt(ip);
        rateLimiter.recordFailedAttempt(ip);
        assertThat(rateLimiter.isBlocked(ip)).isFalse();

        rateLimiter.recordSuccessfulLogin(ip);

        // Can fail again without immediate lockout
        rateLimiter.recordFailedAttempt(ip);
        assertThat(rateLimiter.isBlocked(ip)).isFalse();
    }
}
