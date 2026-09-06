package com.recallbot.admin.dto;

import java.util.List;

public record AdminGroupDetailDto(
        AdminGroupDto group,
        long embeddingCount,
        List<AdminMemberDto> members,
        List<AdminMessageDto> recentMessages,
        List<AdminMemoryDto> recentMemories
) {}
