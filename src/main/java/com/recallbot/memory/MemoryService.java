package com.recallbot.memory;

import com.recallbot.ai.EmbeddingService;
import com.recallbot.config.properties.RecallProperties;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupRepository;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing explicit and derived group memories, source attribution linking,
 * and orphaned memory lifecycle cleanup.
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final MemoryRepository memoryRepository;
    private final MemorySourceRepository memorySourceRepository;
    private final MessageRepository messageRepository;
    private final GroupRepository groupRepository;
    private final UserRepository userRepository;
    private final EmbeddingService embeddingService;
    private final RecallProperties properties;
    private final JdbcClient jdbcClient;

    public MemoryService(
            MemoryRepository memoryRepository,
            MemorySourceRepository memorySourceRepository,
            MessageRepository messageRepository,
            GroupRepository groupRepository,
            UserRepository userRepository,
            EmbeddingService embeddingService,
            RecallProperties properties,
            JdbcClient jdbcClient
    ) {
        this.memoryRepository = Objects.requireNonNull(memoryRepository, "memoryRepository must not be null");
        this.memorySourceRepository = Objects.requireNonNull(memorySourceRepository, "memorySourceRepository must not be null");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository must not be null");
        this.groupRepository = Objects.requireNonNull(groupRepository, "groupRepository must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.embeddingService = Objects.requireNonNull(embeddingService, "embeddingService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /**
     * Records an explicit memory stated via /remember in a group chat.
     * Generates a 1536-dimensional vector embedding, saves to the memories table,
     * and links the command's message as a supporting source.
     *
     * @param groupId           the group database ID
     * @param userId            the authoring user database ID
     * @param telegramMessageId the Telegram message ID of the /remember command
     * @param content           the explicit statement to record
     * @return the persisted MemoryEntity
     */
    @Transactional
    public MemoryEntity recordExplicitMemory(Long groupId, Long userId, Long telegramMessageId, String content) {
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Memory content must not be blank");
        }

        GroupEntity group = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found with id=" + groupId));

        String trimmedContent = content.trim();
        MemoryType type = detectMemoryType(trimmedContent);

        // Generate vector embedding
        float[] vector = embeddingService.generateEmbedding(trimmedContent);
        int expectedDimension = properties.ai().embeddingDimension();
        if (vector.length != expectedDimension) {
            throw new IllegalStateException(String.format(
                    "Embedding vector dimension mismatch: expected %d, got %d",
                    expectedDimension, vector.length));
        }

        MemoryEntity memory = new MemoryEntity();
        memory.setGroup(group);
        memory.setMemoryType(type);
        memory.setContent(trimmedContent);
        memory.setConfidence(BigDecimal.valueOf(1.00));
        memory.setEmbedding(vector);
        memory.setModelName(properties.ai().embeddingModel());

        MemoryEntity savedMemory = memoryRepository.save(memory);
        log.info("Recorded explicit memory id={} for group_id={}, type={}",
                savedMemory.getId(), groupId, type);

        // Link source message if available, creating synthetic message if command was not ingested
        if (telegramMessageId != null) {
            MessageEntity sourceMessage = messageRepository.findByGroupIdAndTelegramMessageId(groupId, telegramMessageId)
                    .orElseGet(() -> {
                        UserEntity author = (userId != null) ? userRepository.findById(userId).orElse(null) : null;
                        MessageEntity newMsg = new MessageEntity();
                        newMsg.setGroup(group);
                        newMsg.setUser(author);
                        newMsg.setTelegramMessageId(telegramMessageId);
                        newMsg.setContent(trimmedContent);
                        newMsg.setSentAt(Instant.now());
                        return messageRepository.save(newMsg);
                    });
            MemorySourceEntity source = new MemorySourceEntity(savedMemory, sourceMessage);
            memorySourceRepository.save(source);
            log.debug("Linked memory_id={} to source message_id={}", savedMemory.getId(), sourceMessage.getId());
        }

        return savedMemory;
    }

    /**
     * Records a derived memory extracted asynchronously from conversation history.
     *
     * @param groupId          the group database ID
     * @param type             the memory type (DECISION, COMMITMENT, FACT)
     * @param content          the distilled statement
     * @param confidence       confidence score between 0.00 and 1.00
     * @param sourceMessageIds list of supporting message database primary keys
     * @return the persisted MemoryEntity
     */
    @Transactional
    public MemoryEntity recordDerivedMemory(
            Long groupId,
            MemoryType type,
            String content,
            BigDecimal confidence,
            List<Long> sourceMessageIds
    ) {
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Derived memory content must not be blank");
        }

        GroupEntity group = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found with id=" + groupId));

        String trimmedContent = content.trim();
        float[] vector = embeddingService.generateEmbedding(trimmedContent);

        MemoryEntity memory = new MemoryEntity();
        memory.setGroup(group);
        memory.setMemoryType(type != null ? type : MemoryType.FACT);
        memory.setContent(trimmedContent);
        memory.setConfidence(confidence != null ? confidence : BigDecimal.valueOf(1.00));
        memory.setEmbedding(vector);
        memory.setModelName(properties.ai().embeddingModel());

        MemoryEntity savedMemory = memoryRepository.save(memory);

        if (sourceMessageIds != null && !sourceMessageIds.isEmpty()) {
            for (Long msgId : sourceMessageIds) {
                if (msgId != null) {
                    messageRepository.findById(msgId).ifPresent(msg -> {
                        if (msg.getGroup() != null && Objects.equals(msg.getGroup().getId(), group.getId())) {
                            MemorySourceEntity source = new MemorySourceEntity(savedMemory, msg);
                            memorySourceRepository.save(source);
                        }
                    });
                }
            }
        }

        log.info("Recorded derived memory id={} for group_id={}, type={}, sources={}",
                savedMemory.getId(), groupId, type, sourceMessageIds != null ? sourceMessageIds.size() : 0);
        return savedMemory;
    }

    /**
     * Deletes any memories in the given group that have lost all supporting source messages.
     *
     * @param groupId the group database ID
     * @return number of orphaned memories deleted
     */
    @Transactional
    public int cleanupOrphanedMemories(Long groupId) {
        if (groupId == null) {
            return 0;
        }

        int deletedCount = jdbcClient.sql("""
                DELETE FROM memories
                WHERE group_id = :groupId
                  AND id NOT IN (SELECT DISTINCT memory_id FROM memory_sources)
                """)
                .param("groupId", groupId)
                .update();

        if (deletedCount > 0) {
            log.info("Cleaned up {} orphaned memories for group_id={}", deletedCount, groupId);
        }
        return deletedCount;
    }

    @Transactional(readOnly = true)
    public List<MemoryEntity> getMemoriesByGroup(Long groupId) {
        if (groupId == null) {
            return List.of();
        }
        return memoryRepository.findByGroupId(groupId);
    }

    @Transactional(readOnly = true)
    public Optional<MemoryEntity> findById(Long memoryId) {
        return memoryRepository.findById(memoryId);
    }

    @Transactional
    public void deleteMemory(Long memoryId) {
        memoryRepository.deleteById(memoryId);
    }

    private MemoryType detectMemoryType(String text) {
        String lower = text.toLowerCase();
        if (lower.contains("decid") || lower.contains("agreed") || lower.contains("chosen") || lower.contains("selected")) {
            return MemoryType.DECISION;
        }
        if (lower.contains("will ") || lower.contains("promise") || lower.contains("committed") || lower.contains("assigned to")) {
            return MemoryType.COMMITMENT;
        }
        return MemoryType.FACT;
    }
}
