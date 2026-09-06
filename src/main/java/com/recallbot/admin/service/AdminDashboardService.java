package com.recallbot.admin.service;

import com.recallbot.admin.dto.DashboardSummaryDto;
import com.recallbot.config.properties.RecallProperties;
import com.recallbot.core.group.GroupRepository;
import com.recallbot.core.message.MessageEmbeddingRepository;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.core.user.UserRepository;
import com.recallbot.memory.MemoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

@Service
public class AdminDashboardService {

    private final GroupRepository groupRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final MemoryRepository memoryRepository;
    private final MessageEmbeddingRepository messageEmbeddingRepository;
    private final RecallProperties properties;
    private final DataSource dataSource;

    public AdminDashboardService(
            GroupRepository groupRepository,
            UserRepository userRepository,
            MessageRepository messageRepository,
            MemoryRepository memoryRepository,
            MessageEmbeddingRepository messageEmbeddingRepository,
            RecallProperties properties,
            DataSource dataSource
    ) {
        this.groupRepository = groupRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.memoryRepository = memoryRepository;
        this.messageEmbeddingRepository = messageEmbeddingRepository;
        this.properties = properties;
        this.dataSource = dataSource;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryDto getDashboardSummary() {
        long totalGroups = groupRepository.count();
        long activeGroups = groupRepository.countByIsActiveTrue();
        long totalUsers = userRepository.count();
        long totalMessages = messageRepository.count();
        long totalMemories = memoryRepository.count();
        long totalEmbeddings = messageEmbeddingRepository.count();
        long pendingEmbeddings = Math.max(0, totalMessages - totalEmbeddings);

        DashboardSummaryDto.Stats stats = new DashboardSummaryDto.Stats(
                totalGroups,
                activeGroups,
                totalUsers,
                totalMessages,
                totalMemories,
                totalEmbeddings,
                pendingEmbeddings
        );

        String dbHealth = checkDatabaseHealth();
        String pgvectorHealth = checkPgvectorHealth();
        String aiHealth = checkAiHealth();

        DashboardSummaryDto.Health health = new DashboardSummaryDto.Health(
                "UP",
                dbHealth,
                pgvectorHealth,
                aiHealth
        );

        String chatModel = (properties != null && properties.ai() != null) ? properties.ai().chatModel() : "unknown";
        String embeddingModel = (properties != null && properties.ai() != null) ? properties.ai().embeddingModel() : "unknown";
        int embeddingDimension = (properties != null && properties.ai() != null) ? properties.ai().embeddingDimension() : 1536;

        DashboardSummaryDto.AiConfig aiConfig = new DashboardSummaryDto.AiConfig(
                chatModel,
                embeddingModel,
                embeddingDimension
        );

        return new DashboardSummaryDto(stats, health, aiConfig);
    }

    private String checkDatabaseHealth() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
            return "CONNECTED";
        } catch (Exception e) {
            return "DEGRADED";
        }
    }

    private String checkPgvectorHealth() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT count(*) FROM pg_extension WHERE extname = 'vector'");
            if (rs.next() && rs.getInt(1) > 0) {
                return "AVAILABLE";
            }
            return "UNAVAILABLE";
        } catch (Exception e) {
            return "UNAVAILABLE";
        }
    }

    private String checkAiHealth() {
        if (properties == null || properties.ai() == null) {
            return "UNCONFIGURED";
        }
        String key = properties.ai().openrouterApiKey();
        if (key == null || key.isBlank() || "placeholder_api_key".equals(key)) {
            return "CONFIG_MISSING";
        }
        return "OPERATIONAL";
    }
}
