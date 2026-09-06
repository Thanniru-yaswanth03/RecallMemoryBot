package com.recallbot.admin.dto;

public record DashboardSummaryDto(
        Stats stats,
        Health health,
        AiConfig aiConfig
) {
    public record Stats(
            long totalGroups,
            long activeGroups,
            long totalUsers,
            long totalMessages,
            long totalMemories,
            long totalEmbeddings,
            long pendingEmbeddings
    ) {}

    public record Health(
            String appStatus,
            String database,
            String pgvector,
            String aiProvider
    ) {}

    public record AiConfig(
            String chatModel,
            String embeddingModel,
            int embeddingDimension
    ) {}
}
