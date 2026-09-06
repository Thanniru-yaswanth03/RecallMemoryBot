package com.recallbot.admin.dto;

import java.time.Instant;

public record AdminMessageDto(
        Long id,
        Long groupId,
        String groupTitle,
        Long userId,
        String authorName,
        String username,
        Long telegramMessageId,
        Long replyToTelegramMessageId,
        String contentSnippet,
        String messageType,
        boolean hasEmbedding,
        Instant sentAt,
        Instant editedAt
) {}
