package com.recallbot.admin.dto;

import java.time.Instant;

public record AdminMemberDto(
        Long id,
        Long userId,
        Long telegramUserId,
        String username,
        String firstName,
        String lastName,
        String role,
        Instant joinedAt
) {}
