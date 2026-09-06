package com.recallbot.search.dto;

import java.time.Instant;

/**
 * Represents a semantically retrieved message hit with metadata and vector relevance distance.
 *
 * @param messageId         Internal primary key of the message
 * @param telegramMessageId Telegram message ID for citation, reply, and source verification
 * @param groupId           Internal group ID ensuring tenant boundary
 * @param userId            Internal author user ID
 * @param username          Telegram username of the author (if present)
 * @param firstName         First name of the author
 * @param content           Message text content
 * @param sentAt            Timestamp when message was sent
 * @param distance          Cosine distance (embedding <=> query_vector); 0.0 indicates identical vectors
 */
public record SearchHit(
        Long messageId,
        Long telegramMessageId,
        Long groupId,
        Long userId,
        String username,
        String firstName,
        String content,
        Instant sentAt,
        double distance
) {
    /**
     * Cosine similarity derived as (1.0 - distance).
     * Higher values indicate greater semantic similarity.
     */
    public double similarity() {
        return 1.0 - distance;
    }
}
