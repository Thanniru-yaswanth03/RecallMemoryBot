package com.recallbot.memory;

import com.pgvector.PGvector;
import com.recallbot.ai.EmbeddingService;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipRepository;
import com.recallbot.core.group.GroupMembershipService;
import com.recallbot.core.group.GroupRepository;
import com.recallbot.core.group.GroupRole;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserRepository;
import com.recallbot.core.user.UserService;
import com.recallbot.persistence.BasePostgresIntegrationTest;
import com.recallbot.privacy.PrivacyService;
import com.recallbot.search.dto.SearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class MemoryPipelineIT extends BasePostgresIntegrationTest {

    private static final int DIMENSION = 1536;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MemoryRepository memoryRepository;

    @Autowired
    private MemorySourceRepository memorySourceRepository;

    @Autowired
    private GroupMembershipRepository groupMembershipRepository;

    @Autowired
    private GroupMembershipService groupMembershipService;

    @Autowired
    private UserService userService;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private PrivacyService privacyService;

    @Autowired
    private com.recallbot.search.SemanticSearchService semanticSearchService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @MockBean
    private EmbeddingService embeddingService;

    private GroupEntity testGroup;
    private UserEntity testUser;

    @BeforeEach
    void setUp() {
        // Mock 1536-dimensional vector embedding
        float[] vector = new float[DIMENSION];
        vector[0] = 0.8f;
        vector[1] = -0.2f;
        when(embeddingService.generateEmbedding(anyString())).thenReturn(vector);
        when(embeddingService.getDimension()).thenReturn(DIMENSION);

        long uniqueChatId = -900000000L - System.currentTimeMillis() % 1000000L;
        testGroup = groupRepository.save(new GroupEntity(uniqueChatId, "Integration Group " + UUID.randomUUID()));

        long uniqueUserId = 800000L + System.currentTimeMillis() % 100000L;
        testUser = userRepository.save(new UserEntity(uniqueUserId, "testuser", "Test", null));

        groupMembershipService.ensureMembership(testGroup, testUser, GroupRole.MEMBER);
    }

    @Test
    @DisplayName("Explicit memory recording persists to memories table with 1536-d vector and links source message")
    void recordExplicitMemoryEndToEnd() {
        MessageEntity message = new MessageEntity();
        message.setGroup(testGroup);
        message.setUser(testUser);
        message.setTelegramMessageId(101L);
        message.setContent("We decided to use PostgreSQL for reliable ACID storage.");
        message.setSentAt(Instant.now());
        MessageEntity savedMessage = messageRepository.save(message);

        MemoryEntity memory = memoryService.recordExplicitMemory(
                testGroup.getId(),
                testUser.getId(),
                101L,
                "We decided to use PostgreSQL for reliable ACID storage."
        );

        assertThat(memory.getId()).isNotNull();
        assertThat(memory.getContent()).isEqualTo("We decided to use PostgreSQL for reliable ACID storage.");
        assertThat(memory.getMemoryType()).isEqualTo(MemoryType.DECISION);
        assertThat(memory.getConfidence()).isEqualByComparingTo("1.00");
        assertThat(memory.getEmbedding()).hasSize(DIMENSION);

        // Verify source link
        List<MemorySourceEntity> sources = memorySourceRepository.findByMemoryId(memory.getId());
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getMessage().getId()).isEqualTo(savedMessage.getId());

        // Verify native pgvector query on memories
        float[] queryVector = new float[DIMENSION];
        queryVector[0] = 0.8f;
        queryVector[1] = -0.2f;

        Double distance = jdbcClient.sql("""
                SELECT (embedding <=> ?::vector) AS distance
                FROM memories
                WHERE id = ?
                """)
                .param(new PGvector(queryVector))
                .param(memory.getId())
                .query(Double.class)
                .single();

        assertThat(distance).isNotNull();
        assertThat(distance).isLessThan(0.01);
    }

    @Test
    @DisplayName("forgetUser preserves shared decision messages by anonymizing to [Former Member], while deleting personal chatter")
    void forgetUserPreservesSharedKnowledgeAndDeletesChatter() {
        // Message 1: supports a shared decision
        MessageEntity decisionMsg = new MessageEntity();
        decisionMsg.setGroup(testGroup);
        decisionMsg.setUser(testUser);
        decisionMsg.setTelegramMessageId(201L);
        decisionMsg.setContent("Agreed on PostgreSQL for V1.");
        decisionMsg.setSentAt(Instant.now());
        MessageEntity savedDecisionMsg = messageRepository.save(decisionMsg);

        MemoryEntity memory = memoryService.recordExplicitMemory(
                testGroup.getId(),
                testUser.getId(),
                201L,
                "Agreed on PostgreSQL for V1."
        );

        // Message 2: unreferenced personal chatter
        MessageEntity chatterMsg = new MessageEntity();
        chatterMsg.setGroup(testGroup);
        chatterMsg.setUser(testUser);
        chatterMsg.setTelegramMessageId(202L);
        chatterMsg.setContent("What's for lunch today?");
        chatterMsg.setSentAt(Instant.now());
        messageRepository.save(chatterMsg);

        // Execute /forget me
        PrivacyService.ForgetMeResult result = privacyService.forgetUser(
                testGroup.getId(),
                testUser.getTelegramUserId()
        );

        assertThat(result.anonymizedMessages()).isEqualTo(1);
        assertThat(result.deletedMessages()).isEqualTo(1);

        // Verify Decision Message is retained and anonymized to [Former Member]
        Optional<MessageEntity> anonymizedMsgOpt = messageRepository.findByGroupIdAndTelegramMessageId(
                testGroup.getId(), 201L
        );
        assertThat(anonymizedMsgOpt).isPresent();
        assertThat(anonymizedMsgOpt.get().getUser().getFirstName()).isEqualTo("[Former Member]");

        // Verify Chatter Message is completely deleted
        Optional<MessageEntity> deletedMsgOpt = messageRepository.findByGroupIdAndTelegramMessageId(
                testGroup.getId(), 202L
        );
        assertThat(deletedMsgOpt).isEmpty();

        // Verify Memory remains active with provenance
        Optional<MemoryEntity> retainedMemoryOpt = memoryRepository.findById(memory.getId());
        assertThat(retainedMemoryOpt).isPresent();

        // Verify membership removed
        assertThat(groupMembershipRepository.findByGroupIdAndUserId(testGroup.getId(), testUser.getId())).isEmpty();
    }

    @Test
    @DisplayName("forgetSingleMessage deletes message and cleans up orphaned memories when source count reaches zero")
    void forgetSingleMessageCleansUpOrphanedMemory() {
        MessageEntity msg = new MessageEntity();
        msg.setGroup(testGroup);
        msg.setUser(testUser);
        msg.setTelegramMessageId(301L);
        msg.setContent("Standalone decision to use Redis.");
        msg.setSentAt(Instant.now());
        messageRepository.saveAndFlush(msg);

        MemoryEntity memory = memoryService.recordExplicitMemory(
                testGroup.getId(),
                testUser.getId(),
                301L,
                "Standalone decision to use Redis."
        );
        Long memoryId = memory.getId();

        // Delete message
        boolean deleted = privacyService.forgetSingleMessage(
                testGroup.getId(),
                testUser.getTelegramUserId(),
                301L,
                testGroup.getTelegramChatId()
        );

        assertThat(deleted).isTrue();

        entityManager.flush();
        entityManager.clear();

        assertThat(messageRepository.findByGroupIdAndTelegramMessageId(testGroup.getId(), 301L)).isEmpty();

        // Orphan cleanup should have purged the memory
        assertThat(memoryRepository.findById(memoryId)).isEmpty();
    }

    @Test
    @DisplayName("Explicit /remember creates memory and /recall semantic search retrieves it accurately")
    void rememberAndRecallRetrievalEndToEnd() {
        MemoryEntity saved = memoryService.recordExplicitMemory(
                testGroup.getId(),
                testUser.getId(),
                999L,
                "We decided to use PostgreSQL with pgvector for RecallMemoryBot."
        );

        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();

        entityManager.flush();
        entityManager.clear();

        List<SearchHit> hits = semanticSearchService.search(
                testGroup.getId(),
                "what database did we decide to use?",
                10
        );

        assertThat(hits).isNotEmpty();
        SearchHit topHit = hits.get(0);
        assertThat(topHit.content()).isEqualTo("We decided to use PostgreSQL with pgvector for RecallMemoryBot.");
        assertThat(topHit.groupId()).isEqualTo(testGroup.getId());
    }
}
