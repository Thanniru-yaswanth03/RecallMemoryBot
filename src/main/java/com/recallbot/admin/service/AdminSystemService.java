package com.recallbot.admin.service;

import com.recallbot.admin.dto.AdminSystemHealthDto;
import com.recallbot.config.properties.RecallProperties;
import com.recallbot.core.message.MessageEmbeddingRepository;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.telegram.TelegramUpdateRepository;
import com.recallbot.telegram.TelegramUpdateStatus;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

@Service
public class AdminSystemService {

    private final DataSource dataSource;
    private final MessageRepository messageRepository;
    private final MessageEmbeddingRepository messageEmbeddingRepository;
    private final TelegramUpdateRepository telegramUpdateRepository;
    private final RecallProperties properties;

    public AdminSystemService(
            DataSource dataSource,
            MessageRepository messageRepository,
            MessageEmbeddingRepository messageEmbeddingRepository,
            TelegramUpdateRepository telegramUpdateRepository,
            RecallProperties properties
    ) {
        this.dataSource = dataSource;
        this.messageRepository = messageRepository;
        this.messageEmbeddingRepository = messageEmbeddingRepository;
        this.telegramUpdateRepository = telegramUpdateRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public AdminSystemHealthDto getSystemDiagnostics() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        double memoryPercent = maxMemory > 0 ? ((double) usedMemory / maxMemory) * 100.0 : 0.0;

        int processors = runtime.availableProcessors();
        int activeThreads = Thread.activeCount();
        String jvmVersion = System.getProperty("java.version");

        // Database pool
        int activeConnections = 0;
        int idleConnections = 0;
        int totalConnections = 0;
        String dbStatus = "CONNECTED";

        if (dataSource instanceof HikariDataSource hikari) {
            var pool = hikari.getHikariPoolMXBean();
            if (pool != null) {
                activeConnections = pool.getActiveConnections();
                idleConnections = pool.getIdleConnections();
                totalConnections = pool.getTotalConnections();
            }
        }

        // pgvector check
        String pgvectorStatus = "AVAILABLE";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT count(*) FROM pg_extension WHERE extname = 'vector'");
            if (!rs.next() || rs.getInt(1) == 0) {
                pgvectorStatus = "UNAVAILABLE";
            }
        } catch (Exception e) {
            dbStatus = "DEGRADED";
            pgvectorStatus = "ERROR";
        }

        // Vectorization coverage
        long totalMessages = messageRepository.count();
        long totalEmbedded = messageEmbeddingRepository.count();
        double coveragePercent = totalMessages > 0 ? ((double) totalEmbedded / totalMessages) * 100.0 : 100.0;

        // AI Provider info
        String chatModel = (properties != null && properties.ai() != null) ? properties.ai().chatModel() : "unknown";
        String embeddingModel = (properties != null && properties.ai() != null) ? properties.ai().embeddingModel() : "unknown";

        // Telegram update stats
        long processedUpdates = telegramUpdateRepository.findAll().stream()
                .filter(u -> u.getStatus() == TelegramUpdateStatus.PROCESSED)
                .count();
        long failedUpdates = telegramUpdateRepository.findAll().stream()
                .filter(u -> u.getStatus() == TelegramUpdateStatus.FAILED)
                .count();

        return new AdminSystemHealthDto(
                "UP",
                jvmVersion,
                processors,
                usedMemory,
                maxMemory,
                Math.round(memoryPercent * 10.0) / 10.0,
                activeThreads,
                dbStatus,
                activeConnections,
                idleConnections,
                totalConnections,
                pgvectorStatus,
                totalMessages,
                totalEmbedded,
                Math.round(coveragePercent * 10.0) / 10.0,
                "OpenRouter",
                chatModel,
                embeddingModel,
                processedUpdates,
                failedUpdates
        );
    }
}
