package com.recallbot.security;

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
import com.recallbot.memory.MemoryEntity;
import com.recallbot.memory.MemoryRepository;
import com.recallbot.memory.MemoryType;
import com.recallbot.persistence.BasePostgresIntegrationTest;
import com.recallbot.search.SemanticSearchService;
import com.recallbot.search.dto.SearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class CrossGroupIsolationSecurityTest extends BasePostgresIntegrationTest {

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
    private GroupMembershipRepository groupMembershipRepository;
    @Autowired
    private GroupMembershipService groupMembershipService;
    @Autowired
    private SemanticSearchService semanticSearchService;
    @Autowired
    private JdbcClient jdbcClient;

    @MockBean
    private EmbeddingService embeddingService;

    private GroupEntity groupA;
    private GroupEntity groupB;
    private UserEntity userA;
    private UserEntity userB;

    @BeforeEach
    void setUp() {
        when(embeddingService.getDimension()).thenReturn(DIMENSION);

        long uniqueChatA = -1001000000L - (System.currentTimeMillis() % 100000L);
        long uniqueChatB = -1002000000L - (System.currentTimeMillis() % 100000L);
        groupA = groupRepository.save(new GroupEntity(uniqueChatA, "Group A - Public " + UUID.randomUUID()));
        groupB = groupRepository.save(new GroupEntity(uniqueChatB, "Group B - Confidential " + UUID.randomUUID()));

        userA = userRepository.save(new UserEntity(70001L, "alice", "Alice", null));
        userB = userRepository.save(new UserEntity(70002L, "bob", "Bob", null));

        groupMembershipService.ensureMembership(groupA, userA, GroupRole.MEMBER);
        groupMembershipService.ensureMembership(groupB, userB, GroupRole.MEMBER);
    }

    @Test
    @DisplayName("Group A search strictly cannot retrieve confidential messages stored in Group B")
    void groupACannotRetrieveGroupBMessages() {
        // Group B: store confidential database credential message
        MessageEntity secretMsg = messageRepository.save(
                new MessageEntity(groupB, userB, 901L, "Production DB Password is SUPER_SECRET_PWD_999", Instant.now())
        );
        float[] secretVector = createUnitVector(0, 1.0f);
        saveMessageEmbedding(secretMsg.getId(), groupB.getId(), secretVector);

        // Group A: store unrelated message
        MessageEntity publicMsg = messageRepository.save(
                new MessageEntity(groupA, userA, 101L, "Welcome to Group A chat", Instant.now())
        );
        float[] normalVector = createUnitVector(1, 1.0f);
        saveMessageEmbedding(publicMsg.getId(), groupA.getId(), normalVector);

        // Query asking for password (vector aligns with secretVector)
        when(embeddingService.generateEmbedding(anyString())).thenReturn(secretVector);

        // Search executed in Group A
        List<SearchHit> hitsA = semanticSearchService.search(groupA.getId(), "what is the production DB password?", 10);

        // Assert 0 leakage of Group B message
        assertThat(hitsA).noneMatch(hit -> hit.content().contains("SUPER_SECRET_PWD_999"));
        assertThat(hitsA).noneMatch(hit -> hit.groupId().equals(groupB.getId()));
    }

    @Test
    @DisplayName("Group A search strictly cannot retrieve explicit memories stored in Group B")
    void groupACannotRetrieveGroupBMemories() {
        // Group B: explicit decision memory
        float[] memVector = createUnitVector(0, 1.0f);
        MemoryEntity memoryB = new MemoryEntity();
        memoryB.setGroup(groupB);
        memoryB.setMemoryType(MemoryType.DECISION);
        memoryB.setContent("We decided the secret deployment key is KEY_DEPLOY_XYZ_888.");
        memoryB.setEmbedding(memVector);
        memoryB.setConfidence(java.math.BigDecimal.ONE);
        memoryB.setModelName("openai/text-embedding-3-small");
        memoryRepository.save(memoryB);

        // Vector query aligned with memory
        when(embeddingService.generateEmbedding(anyString())).thenReturn(memVector);

        // Search executed in Group A
        List<SearchHit> hitsA = semanticSearchService.search(groupA.getId(), "what is the secret deployment key?", 10);

        // Assert 0 leakage of Group B memory
        assertThat(hitsA).noneMatch(hit -> hit.content().contains("KEY_DEPLOY_XYZ_888"));
        assertThat(hitsA).noneMatch(hit -> hit.groupId().equals(groupB.getId()));
    }

    @Test
    @DisplayName("Direct vector search using searchByVector strictly isolates by group_id at SQL boundary")
    void directVectorSearchIsolatesByGroupIdAtDatabaseBoundary() {
        MessageEntity secretMsg = messageRepository.save(
                new MessageEntity(groupB, userB, 902L, "Top Secret Strategy Document", Instant.now())
        );
        float[] targetVector = createUnitVector(10, 1.0f);
        saveMessageEmbedding(secretMsg.getId(), groupB.getId(), targetVector);

        // Group A executes direct vector search with exact target vector
        List<SearchHit> hitsA = semanticSearchService.searchByVector(groupA.getId(), targetVector, 10);
        assertThat(hitsA).isEmpty();

        // Group B executes same search: retrieves the record
        List<SearchHit> hitsB = semanticSearchService.searchByVector(groupB.getId(), targetVector, 10);
        assertThat(hitsB).hasSize(1);
        assertThat(hitsB.get(0).content()).isEqualTo("Top Secret Strategy Document");
    }

    @Test
    @DisplayName("Null group_id in search methods fails closed with IllegalArgumentException")
    void nullGroupIdFailsClosed() {
        float[] vector = createUnitVector(0, 1.0f);

        assertThatThrownBy(() -> semanticSearchService.search(null, "query", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupId must not be null");

        assertThatThrownBy(() -> semanticSearchService.search(null, "query"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupId must not be null");

        assertThatThrownBy(() -> semanticSearchService.searchByVector(null, vector, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupId must not be null");
    }

    @Test
    @DisplayName("TenantContext safely scopes execution and cleans up ThreadLocal to prevent leakage")
    void tenantContextLifecycleAndCleanliness() {
        assertThat(TenantContext.getGroupId()).isEmpty();

        try (TenantContext.TenantScope scope = TenantContext.with(groupA.getId())) {
            assertThat(TenantContext.getGroupId()).contains(groupA.getId());
        }

        // Must be empty after try-with-resources
        assertThat(TenantContext.getGroupId()).isEmpty();
    }

    private void saveMessageEmbedding(Long messageId, Long groupId, float[] vector) {
        PGvector pgVector = new PGvector(vector);
        jdbcClient.sql("""
                INSERT INTO message_embeddings (message_id, group_id, embedding, model_name, created_at)
                VALUES (?, ?, ?::vector, 'openai/text-embedding-3-small', CURRENT_TIMESTAMP)
                """)
                .param(messageId)
                .param(groupId)
                .param(pgVector)
                .update();
    }

    private float[] createUnitVector(int nonZeroIndex, float value) {
        float[] vec = new float[DIMENSION];
        if (nonZeroIndex >= 0 && nonZeroIndex < DIMENSION) {
            vec[nonZeroIndex] = value;
        }
        return vec;
    }
}
