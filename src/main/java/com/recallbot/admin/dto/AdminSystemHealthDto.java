package com.recallbot.admin.dto;

public record AdminSystemHealthDto(
        String appStatus,
        String jvmVersion,
        int availableProcessors,
        long heapUsedBytes,
        long heapMaxBytes,
        double heapUsedPercentage,
        int activeThreads,
        String dbStatus,
        int dbActiveConnections,
        int dbIdleConnections,
        int dbTotalConnections,
        String pgvectorStatus,
        long totalMessages,
        long totalEmbedded,
        double embeddingCoveragePercent,
        String aiProvider,
        String aiChatModel,
        String aiEmbeddingModel,
        long totalUpdatesProcessed,
        long totalUpdatesFailed
) {}
