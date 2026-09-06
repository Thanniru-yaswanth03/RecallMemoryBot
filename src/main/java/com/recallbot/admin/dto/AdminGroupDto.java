package com.recallbot.admin.dto;

import java.time.Instant;

public record AdminGroupDto(
        Long id,
        Long telegramChatId,
        String title,
        boolean isActive,
        Integer retentionDays,
        long memberCount,
        long messageCount,
        long memoryCount,
        Instant lastActivityAt,
        Instant createdAt
) {}
