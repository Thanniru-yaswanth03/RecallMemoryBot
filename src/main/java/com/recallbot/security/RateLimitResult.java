package com.recallbot.security;

/**
 * Result of a rate limiter evaluation.
 * Contains whether the request was permitted, the reason if rejected,
 * the suggested cooldown/retry-after period, and a user-friendly error message.
 */
public record RateLimitResult(
        boolean allowed,
        String reason,
        long retryAfterSeconds,
        String errorMessage
) {
    public static final String REASON_ALLOWED = "ALLOWED";
    public static final String REASON_USER_LIMIT_EXCEEDED = "USER_LIMIT_EXCEEDED";
    public static final String REASON_GROUP_LIMIT_EXCEEDED = "GROUP_LIMIT_EXCEEDED";

    public static RateLimitResult permit() {
        return new RateLimitResult(true, REASON_ALLOWED, 0, null);
    }

    public static RateLimitResult userLimitExceeded(long retryAfterSeconds) {
        long waitSec = Math.max(1, retryAfterSeconds);
        String message = String.format(
                "You are asking questions too frequently in this group. Please wait %d second%s before asking another question.",
                waitSec, waitSec == 1 ? "" : "s"
        );
        return new RateLimitResult(false, REASON_USER_LIMIT_EXCEEDED, waitSec, message);
    }

    public static RateLimitResult groupLimitExceeded(long retryAfterSeconds) {
        long waitSec = Math.max(1, retryAfterSeconds);
        String message = String.format(
                "This group has reached its question limit. Please wait %d second%s before asking another question.",
                waitSec, waitSec == 1 ? "" : "s"
        );
        return new RateLimitResult(false, REASON_GROUP_LIMIT_EXCEEDED, waitSec, message);
    }

    public boolean isAllowed() {
        return allowed;
    }
}
