package com.recallbot.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AdminMemoryDto(
        Long id,
        Long groupId,
        String groupTitle,
        String memoryType,
        String content,
        BigDecimal confidence,
        String modelName,
        Instant createdAt,
        Instant updatedAt,
        List<MemorySourceDto> sources
) {}
