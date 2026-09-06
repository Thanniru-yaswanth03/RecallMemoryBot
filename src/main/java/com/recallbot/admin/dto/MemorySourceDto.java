package com.recallbot.admin.dto;

import java.time.Instant;

public record MemorySourceDto(
        Long messageId,
        Long telegramMessageId,
        String authorName,
        String username,
        Instant sentAt
) {}
