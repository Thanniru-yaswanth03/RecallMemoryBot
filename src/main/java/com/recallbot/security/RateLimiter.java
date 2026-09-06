package com.recallbot.security;

import com.recallbot.config.properties.RecallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory sliding-window rate limiter.
 * Enforces per-(groupId, userId) rate limits (default 3 queries / 60 seconds)
 * and per-groupId rate limits (default 10 queries / 300 seconds) for /ask and /recall commands.
 * Automatically evicts stale timestamp records to prevent memory leakage.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final long USER_WINDOW_MS = 60_000L;       // 1 minute
    private static final long GROUP_WINDOW_MS = 300_000L;     // 5 minutes
    private static final int MAX_ENTRIES_BEFORE_PURGE = 5000;

    private final RecallProperties properties;
    private final Clock clock;

    private final Map<String, Deque<Long>> userBuckets = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> groupBuckets = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RateLimiter(RecallProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public RateLimiter(RecallProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * Checks whether a query from (groupId, userId) is allowed under sliding-window limits.
     * If permitted, records the request timestamp and returns an allowed result.
     * If throttled, returns a descriptive RateLimitResult with retry cooldown.
     *
     * @param groupId the database group identifier
     * @param userId  the database user identifier
     * @return RateLimitResult indicating allowed or rejection reason
     */
    public RateLimitResult tryAcquire(Long groupId, Long userId) {
        if (groupId == null || userId == null) {
            log.warn("RateLimiter invoked with null groupId or userId; defaulting to allowed");
            return RateLimitResult.permit();
        }

        int userLimit = getUserLimit();
        int groupLimit = getGroupLimit();

        long now = clock.millis();
        String userKey = "user:" + groupId + ":" + userId;
        String groupKey = "group:" + groupId;

        // Clean up maps periodically if size becomes large
        if (userBuckets.size() > MAX_ENTRIES_BEFORE_PURGE) {
            pruneStaleBuckets(userBuckets, USER_WINDOW_MS, now);
        }
        if (groupBuckets.size() > MAX_ENTRIES_BEFORE_PURGE) {
            pruneStaleBuckets(groupBuckets, GROUP_WINDOW_MS, now);
        }

        Deque<Long> userQueue = userBuckets.computeIfAbsent(userKey, k -> new ArrayDeque<>());
        Deque<Long> groupQueue = groupBuckets.computeIfAbsent(groupKey, k -> new ArrayDeque<>());

        // Synchronize on user queue first to evaluate and potentially throttle per-user
        synchronized (userQueue) {
            purgeExpiredTimestamps(userQueue, now - USER_WINDOW_MS);

            if (userQueue.size() >= userLimit) {
                Long oldest = userQueue.peekFirst();
                long retryAfterSec = calculateRetryAfterSeconds(oldest, USER_WINDOW_MS, now);
                log.info("Rate limit exceeded for user_key={}, size={}, limit={}, retryAfterSec={}",
                        userKey, userQueue.size(), userLimit, retryAfterSec);
                return RateLimitResult.userLimitExceeded(retryAfterSec);
            }
        }

        // Synchronize on group queue to evaluate per-group traffic
        synchronized (groupQueue) {
            purgeExpiredTimestamps(groupQueue, now - GROUP_WINDOW_MS);

            if (groupQueue.size() >= groupLimit) {
                Long oldest = groupQueue.peekFirst();
                long retryAfterSec = calculateRetryAfterSeconds(oldest, GROUP_WINDOW_MS, now);
                log.info("Rate limit exceeded for group_key={}, size={}, limit={}, retryAfterSec={}",
                        groupKey, groupQueue.size(), groupLimit, retryAfterSec);
                return RateLimitResult.groupLimitExceeded(retryAfterSec);
            }
        }

        // Both checks passed: record timestamps atomically
        synchronized (userQueue) {
            userQueue.addLast(now);
        }
        synchronized (groupQueue) {
            groupQueue.addLast(now);
        }

        return RateLimitResult.permit();
    }

    /**
     * Resets all stored buckets (used for testing or maintenance).
     */
    public void reset() {
        userBuckets.clear();
        groupBuckets.clear();
    }

    private int getUserLimit() {
        if (properties != null && properties.rateLimit() != null && properties.rateLimit().userPerMinute() > 0) {
            return properties.rateLimit().userPerMinute();
        }
        return 3;
    }

    private int getGroupLimit() {
        if (properties != null && properties.rateLimit() != null && properties.rateLimit().groupPerFiveMinutes() > 0) {
            return properties.rateLimit().groupPerFiveMinutes();
        }
        return 10;
    }

    private void purgeExpiredTimestamps(Deque<Long> queue, long cutoff) {
        while (!queue.isEmpty() && queue.peekFirst() <= cutoff) {
            queue.pollFirst();
        }
    }

    private long calculateRetryAfterSeconds(Long oldestTimestamp, long windowMs, long now) {
        if (oldestTimestamp == null) {
            return 1L;
        }
        long remainingMs = (oldestTimestamp + windowMs) - now;
        return Math.max(1L, (remainingMs + 999L) / 1000L);
    }

    private void pruneStaleBuckets(Map<String, Deque<Long>> buckets, long windowMs, long now) {
        long cutoff = now - windowMs;
        buckets.entrySet().removeIf(entry -> {
            Deque<Long> queue = entry.getValue();
            synchronized (queue) {
                purgeExpiredTimestamps(queue, cutoff);
                return queue.isEmpty();
            }
        });
    }
}
