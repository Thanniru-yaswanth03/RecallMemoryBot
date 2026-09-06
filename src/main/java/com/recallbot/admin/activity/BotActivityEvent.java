package com.recallbot.admin.activity;

import java.time.Instant;

/**
 * Immutable record representing an operational bot activity event.
 * Contains sanitized metadata only; never stores private message contents or prompts.
 */
public record BotActivityEvent(
        String id,
        Instant timestamp,
        String eventType,
        Long groupId,
        String groupTitle,
        String status,
        long durationMs,
        String details
) {
    public BotActivityEvent {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (status == null || status.isBlank()) {
            status = "SUCCESS";
        }
    }
}
