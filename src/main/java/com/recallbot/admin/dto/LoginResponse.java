package com.recallbot.admin.dto;

import java.time.Instant;

public record LoginResponse(
        String status,
        String token,
        String username,
        Instant expiresAt
) {}
