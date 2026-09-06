package com.recallbot.security;

import com.recallbot.config.properties.RecallProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private MutableClock clock;
    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-06T12:00:00Z"));
        RecallProperties properties = new RecallProperties(
                new RecallProperties.Telegram("webhook", "bot", "token", "secret", null),
                new RecallProperties.Ai("key", "chat", "embed", 1536, 30, 800, 0.2),
                new RecallProperties.Search(10, 60, 2500),
                new RecallProperties.RateLimit(3, 10) // 3 per user/min, 10 per group/5min
        );
        rateLimiter = new RateLimiter(properties, clock);
    }

    @Test
    @DisplayName("Allowed requests under user limit pass successfully")
    void allowedRequestsPass() {
        Long groupId = 100L;
        Long userId = 200L;

        assertThat(rateLimiter.tryAcquire(groupId, userId).isAllowed()).isTrue();
        assertThat(rateLimiter.tryAcquire(groupId, userId).isAllowed()).isTrue();
        assertThat(rateLimiter.tryAcquire(groupId, userId).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("User limit rejection: 4th request within 60 seconds is throttled")
    void userLimitRejectionOnFourthRequest() {
        Long groupId = 100L;
        Long userId = 200L;

        rateLimiter.tryAcquire(groupId, userId);
        rateLimiter.tryAcquire(groupId, userId);
        rateLimiter.tryAcquire(groupId, userId);

        RateLimitResult throttled = rateLimiter.tryAcquire(groupId, userId);
        assertThat(throttled.isAllowed()).isFalse();
        assertThat(throttled.reason()).isEqualTo(RateLimitResult.REASON_USER_LIMIT_EXCEEDED);
        assertThat(throttled.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
        assertThat(throttled.errorMessage()).contains("asking questions too frequently");
    }

    @Test
    @DisplayName("Sliding window recovery: user can query again after 60 seconds have elapsed")
    void slidingWindowRecoveryAfterSixtySeconds() {
        Long groupId = 100L;
        Long userId = 200L;

        rateLimiter.tryAcquire(groupId, userId);
        rateLimiter.tryAcquire(groupId, userId);
        rateLimiter.tryAcquire(groupId, userId);

        assertThat(rateLimiter.tryAcquire(groupId, userId).isAllowed()).isFalse();

        // Advance clock by 61 seconds
        clock.advanceMillis(61_000);

        // Window expired, user should be allowed again
        RateLimitResult recovered = rateLimiter.tryAcquire(groupId, userId);
        assertThat(recovered.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("Per-user isolation: User A throttled does not throttle User B in the same group")
    void perUserIsolationWithinSameGroup() {
        Long groupId = 100L;
        Long userA = 201L;
        Long userB = 202L;

        // User A uses up their 3-request quota
        rateLimiter.tryAcquire(groupId, userA);
        rateLimiter.tryAcquire(groupId, userA);
        rateLimiter.tryAcquire(groupId, userA);
        assertThat(rateLimiter.tryAcquire(groupId, userA).isAllowed()).isFalse();

        // User B has fresh quota and should be allowed
        assertThat(rateLimiter.tryAcquire(groupId, userB).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("Group limit rejection: 11th request across different users in 5 minutes throttles the group")
    void groupLimitRejectionOnEleventhRequest() {
        Long groupId = 500L;

        // 10 different users in the group query once each (under user limits)
        for (long u = 1; u <= 10; u++) {
            assertThat(rateLimiter.tryAcquire(groupId, u).isAllowed()).isTrue();
        }

        // 11th query from a fresh user is throttled by the group quota
        RateLimitResult throttled = rateLimiter.tryAcquire(groupId, 999L);
        assertThat(throttled.isAllowed()).isFalse();
        assertThat(throttled.reason()).isEqualTo(RateLimitResult.REASON_GROUP_LIMIT_EXCEEDED);
        assertThat(throttled.errorMessage()).contains("This group has reached its question limit");
    }

    @Test
    @DisplayName("Per-group isolation: Group A throttled does not throttle Group B")
    void perGroupIsolation() {
        Long groupA = 100L;
        Long groupB = 200L;

        for (long u = 1; u <= 10; u++) {
            rateLimiter.tryAcquire(groupA, u);
        }
        assertThat(rateLimiter.tryAcquire(groupA, 999L).isAllowed()).isFalse();

        // Group B is unaffected
        assertThat(rateLimiter.tryAcquire(groupB, 1L).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("Handles null parameters gracefully without throwing")
    void handlesNullParametersGracefully() {
        assertThat(rateLimiter.tryAcquire(null, 1L).isAllowed()).isTrue();
        assertThat(rateLimiter.tryAcquire(1L, null).isAllowed()).isTrue();
        assertThat(rateLimiter.tryAcquire(null, null).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("Concurrent requests are handled safely and accurately enforce limit")
    void concurrentRequestsThreadSafety() throws Exception {
        Long groupId = 777L;
        Long userId = 888L;

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> rateLimiter.tryAcquire(groupId, userId).isAllowed());
        }

        List<Future<Boolean>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        int allowedCount = 0;
        int rejectedCount = 0;

        for (Future<Boolean> future : futures) {
            if (future.get()) {
                allowedCount++;
            } else {
                rejectedCount++;
            }
        }

        // Exactly 3 allowed and 7 rejected
        assertThat(allowedCount).isEqualTo(3);
        assertThat(rejectedCount).isEqualTo(7);
    }

    /**
     * Mutable Clock implementation for deterministic time testing.
     */
    private static class MutableClock extends Clock {
        private final AtomicLong currentMillis;
        private final ZoneId zoneId = ZoneId.of("UTC");

        public MutableClock(Instant start) {
            this.currentMillis = new AtomicLong(start.toEpochMilli());
        }

        public void advanceMillis(long millis) {
            currentMillis.addAndGet(millis);
        }

        @Override
        public ZoneId getZone() {
            return zoneId;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(currentMillis.get());
        }

        @Override
        public long millis() {
            return currentMillis.get();
        }
    }
}
