package com.recallbot.search;

import com.recallbot.ai.EmbeddingService;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupRepository;
import com.recallbot.core.message.MessageEmbeddingEntity;
import com.recallbot.core.message.MessageEmbeddingRepository;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserRepository;
import com.recallbot.persistence.BasePostgresIntegrationTest;
import com.recallbot.search.dto.SearchHit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
class SemanticSearchIT extends BasePostgresIntegrationTest {

    private static final int DIMENSION = 1536;
    private static final String MODEL = "openai/text-embedding-3-small";

    @Autowired
    private SemanticSearchService semanticSearchService;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageEmbeddingRepository messageEmbeddingRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @MockBean
    private EmbeddingService embeddingService;

    @BeforeEach
    void cleanUp() {
        jdbcClient.sql("DELETE FROM message_embeddings").update();
        jdbcClient.sql("DELETE FROM memory_sources").update();
        jdbcClient.sql("DELETE FROM memories").update();
        jdbcClient.sql("DELETE FROM messages").update();
        jdbcClient.sql("DELETE FROM group_memberships").update();
        jdbcClient.sql("DELETE FROM groups").update();
        jdbcClient.sql("DELETE FROM users").update();

        when(embeddingService.getDimension()).thenReturn(DIMENSION);
        when(embeddingService.getModelName()).thenReturn(MODEL);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void saveEmbedding(Long messageId, Long groupId, float[] vector) {
        com.pgvector.PGvector pgVector = new com.pgvector.PGvector(vector);
        jdbcClient.sql("""
                INSERT INTO message_embeddings (message_id, group_id, embedding, model_name, created_at)
                VALUES (?, ?, ?::vector, ?, CURRENT_TIMESTAMP)
                """)
                .param(messageId)
                .param(groupId)
                .param(pgVector)
                .param(MODEL)
                .update();
    }

    private float[] createUnitVector(int nonZeroIndex, float nonZeroValue) {
        float[] vector = new float[DIMENSION];
        vector[nonZeroIndex] = nonZeroValue;
        return vector;
    }

    private float[] createTwoComponentVector(float val0, float val1) {
        float[] vector = new float[DIMENSION];
        vector[0] = val0;
        vector[1] = val1;
        return vector;
    }

    @Test
    @DisplayName("A & C: Semantic retrieval returns relevant messages ordered by cosine distance (similarity)")
    void retrievalReturnsRelevantMessagesOrderedByDistance() {
        GroupEntity group = groupRepository.save(new GroupEntity(-10055667788L, "Engineering Chat"));
        UserEntity user = userRepository.save(new UserEntity(11223344L, "alice", "Alice", "Smith"));

        // Message 1: Vector exactly aligned with query (distance = 0.0)
        MessageEntity m1 = messageRepository.save(new MessageEntity(group, user, 101L, "We decided on PostgreSQL with pgvector", Instant.now()));
        float[] v1 = createTwoComponentVector(1.0f, 0.0f);
        saveEmbedding(m1.getId(), group.getId(), v1);

        // Message 2: Vector moderately close (cos = 0.8, distance = 0.2)
        MessageEntity m2 = messageRepository.save(new MessageEntity(group, user, 102L, "PostgreSQL extensions work very well", Instant.now()));
        float[] v2 = createTwoComponentVector(0.8f, 0.6f);
        saveEmbedding(m2.getId(), group.getId(), v2);

        // Message 3: Vector orthogonal/distant (cos = 0.0, distance = 1.0)
        MessageEntity m3 = messageRepository.save(new MessageEntity(group, user, 103L, "What should we eat for dinner?", Instant.now()));
        float[] v3 = createTwoComponentVector(0.0f, 1.0f);
        saveEmbedding(m3.getId(), group.getId(), v3);

        // Query vector: [1.0, 0.0, ...]
        float[] queryVector = createTwoComponentVector(1.0f, 0.0f);
        when(embeddingService.generateEmbedding("PostgreSQL vector database")).thenReturn(queryVector);

        List<SearchHit> results = semanticSearchService.search(group.getId(), "PostgreSQL vector database", 10);

        assertThat(results).hasSize(3);

        // 1st hit: m1 (exact match, distance ~0.0)
        SearchHit hit1 = results.get(0);
        assertThat(hit1.messageId()).isEqualTo(m1.getId());
        assertThat(hit1.telegramMessageId()).isEqualTo(101L);
        assertThat(hit1.content()).isEqualTo("We decided on PostgreSQL with pgvector");
        assertThat(hit1.username()).isEqualTo("alice");
        assertThat(hit1.firstName()).isEqualTo("Alice");
        assertThat(hit1.distance()).isLessThan(0.0001);
        assertThat(hit1.similarity()).isGreaterThan(0.999);

        // 2nd hit: m2 (moderate match, distance ~0.2)
        SearchHit hit2 = results.get(1);
        assertThat(hit2.messageId()).isEqualTo(m2.getId());
        assertThat(hit2.telegramMessageId()).isEqualTo(102L);
        assertThat(hit2.distance()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.01));

        // 3rd hit: m3 (distant match, distance ~1.0)
        SearchHit hit3 = results.get(2);
        assertThat(hit3.messageId()).isEqualTo(m3.getId());
        assertThat(hit3.telegramMessageId()).isEqualTo(103L);
        assertThat(hit3.distance()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));

        // Verify ordering: hit1.distance < hit2.distance < hit3.distance
        assertThat(hit1.distance()).isLessThan(hit2.distance());
        assertThat(hit2.distance()).isLessThan(hit3.distance());
    }

    @Test
    @DisplayName("B: Retrieval is strictly restricted by group_id; never leaks across groups")
    void resultsAreRestrictedByGroupId() {
        GroupEntity groupAlpha = groupRepository.save(new GroupEntity(-1001111111L, "Group Alpha"));
        GroupEntity groupBeta = groupRepository.save(new GroupEntity(-1002222222L, "Group Beta"));

        UserEntity userAlpha = userRepository.save(new UserEntity(1010L, "alpha_user", "Alpha", null));
        UserEntity userBeta = userRepository.save(new UserEntity(2020L, "beta_user", "Beta", null));

        // Both groups have a message with identical vector
        float[] vector = createUnitVector(0, 1.0f);

        MessageEntity msgAlpha = messageRepository.save(new MessageEntity(groupAlpha, userAlpha, 501L, "Alpha confidential message", Instant.now()));
        saveEmbedding(msgAlpha.getId(), groupAlpha.getId(), vector);

        MessageEntity msgBeta = messageRepository.save(new MessageEntity(groupBeta, userBeta, 502L, "Beta secret message", Instant.now()));
        saveEmbedding(msgBeta.getId(), groupBeta.getId(), vector);

        when(embeddingService.generateEmbedding(anyString())).thenReturn(vector);

        // Querying Group Alpha must ONLY return msgAlpha
        List<SearchHit> alphaResults = semanticSearchService.search(groupAlpha.getId(), "confidential", 10);
        assertThat(alphaResults).hasSize(1);
        assertThat(alphaResults.get(0).messageId()).isEqualTo(msgAlpha.getId());
        assertThat(alphaResults.get(0).groupId()).isEqualTo(groupAlpha.getId());
        assertThat(alphaResults.get(0).content()).isEqualTo("Alpha confidential message");

        // Querying Group Beta must ONLY return msgBeta
        List<SearchHit> betaResults = semanticSearchService.search(groupBeta.getId(), "confidential", 10);
        assertThat(betaResults).hasSize(1);
        assertThat(betaResults.get(0).messageId()).isEqualTo(msgBeta.getId());
        assertThat(betaResults.get(0).groupId()).isEqualTo(groupBeta.getId());
        assertThat(betaResults.get(0).content()).isEqualTo("Beta secret message");
    }

    @Test
    @DisplayName("D: Top-K limits the number of returned results to requested count")
    void topKLimitsReturnedResults() {
        GroupEntity group = groupRepository.save(new GroupEntity(-1003333333L, "TopK Test Group"));
        UserEntity user = userRepository.save(new UserEntity(3030L, "charlie", "Charlie", null));

        // Insert 5 messages with ascending distances
        for (int i = 0; i < 5; i++) {
            MessageEntity msg = messageRepository.save(new MessageEntity(group, user, 600L + i, "Message " + i, Instant.now()));
            float[] vec = createTwoComponentVector(1.0f - (i * 0.15f), (float) Math.sqrt(1.0 - Math.pow(1.0 - (i * 0.15f), 2)));
            saveEmbedding(msg.getId(), group.getId(), vec);
        }

        float[] queryVector = createTwoComponentVector(1.0f, 0.0f);
        when(embeddingService.generateEmbedding("search")).thenReturn(queryVector);

        // Request topK = 2
        List<SearchHit> results = semanticSearchService.search(group.getId(), "search", 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).content()).isEqualTo("Message 0");
        assertThat(results.get(1).content()).isEqualTo("Message 1");
    }

    @Test
    @DisplayName("E: Empty and no-result situations are handled cleanly")
    void emptyAndNoResultSituationsHandledCleanly() {
        GroupEntity emptyGroup = groupRepository.save(new GroupEntity(-1004444444L, "Empty Group"));

        float[] queryVector = createUnitVector(0, 1.0f);
        when(embeddingService.generateEmbedding("query")).thenReturn(queryVector);

        // 1. Querying an empty group returns empty list
        List<SearchHit> emptyHits = semanticSearchService.search(emptyGroup.getId(), "query", 5);
        assertThat(emptyHits).isEmpty();

        // 2. Querying with blank text returns empty list
        assertThat(semanticSearchService.search(emptyGroup.getId(), "   ", 5)).isEmpty();

        // 3. Querying with null text returns empty list
        assertThat(semanticSearchService.search(emptyGroup.getId(), null, 5)).isEmpty();

        // 4. Querying with topK = 0 returns empty list
        assertThat(semanticSearchService.search(emptyGroup.getId(), "query", 0)).isEmpty();
    }

    @Test
    @DisplayName("Direct vector search using searchByVector works accurately")
    void searchByVectorDirectly() {
        GroupEntity group = groupRepository.save(new GroupEntity(-1005555555L, "Direct Vector Group"));
        UserEntity user = userRepository.save(new UserEntity(4040L, "dan", "Dan", null));

        MessageEntity msg = messageRepository.save(new MessageEntity(group, user, 701L, "Direct vector test", Instant.now()));
        float[] vector = createUnitVector(5, 1.0f);
        saveEmbedding(msg.getId(), group.getId(), vector);

        List<SearchHit> hits = semanticSearchService.searchByVector(group.getId(), vector, 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).messageId()).isEqualTo(msg.getId());
        assertThat(hits.get(0).distance()).isLessThan(0.0001);
    }

    @Test
    @DisplayName("Semantic search retrieves both raw message_embeddings and memories records with strict group isolation")
    void searchRetrievesBothMessagesAndMemoriesWithGroupIsolation() {
        GroupEntity groupA = groupRepository.save(new GroupEntity(-1008888888L, "Group A"));
        GroupEntity groupB = groupRepository.save(new GroupEntity(-1009999999L, "Group B"));
        UserEntity user = userRepository.save(new UserEntity(5050L, "eve", "Eve", null));

        // Group A: 1 raw message embedding
        MessageEntity msgA = messageRepository.save(new MessageEntity(groupA, user, 801L, "Raw discussion about database selection", Instant.now()));
        float[] vectorMsg = createTwoComponentVector(0.9f, 0.1f);
        saveEmbedding(msgA.getId(), groupA.getId(), vectorMsg);

        // Group A: 1 explicit memory record (DECISION)
        float[] vectorMem = createTwoComponentVector(1.0f, 0.0f);
        com.pgvector.PGvector pgVectorMem = new com.pgvector.PGvector(vectorMem);
        jdbcClient.sql("""
                INSERT INTO memories (group_id, memory_type, content, confidence, embedding, model_name, created_at)
                VALUES (?, ?, ?, 1.00, ?::vector, ?, CURRENT_TIMESTAMP)
                """)
                .param(groupA.getId())
                .param("DECISION")
                .param("We decided to use PostgreSQL with pgvector for RecallMemoryBot.")
                .param(pgVectorMem)
                .param(MODEL)
                .update();

        // Group B: 1 memory in another group (must NOT leak to Group A)
        jdbcClient.sql("""
                INSERT INTO memories (group_id, memory_type, content, confidence, embedding, model_name, created_at)
                VALUES (?, ?, ?, 1.00, ?::vector, ?, CURRENT_TIMESTAMP)
                """)
                .param(groupB.getId())
                .param("DECISION")
                .param("Group B decided to use MongoDB.")
                .param(pgVectorMem)
                .param(MODEL)
                .update();

        // Search query aligned with vector [1.0, 0.0]
        float[] queryVector = createTwoComponentVector(1.0f, 0.0f);
        when(embeddingService.generateEmbedding("what database did we decide to use?")).thenReturn(queryVector);

        List<SearchHit> resultsA = semanticSearchService.search(groupA.getId(), "what database did we decide to use?", 10);

        // Both the explicit memory and the raw message in Group A are retrieved
        assertThat(resultsA).hasSize(2);

        // Top hit is the explicit memory (distance = 0.0)
        SearchHit topHit = resultsA.get(0);
        assertThat(topHit.content()).isEqualTo("We decided to use PostgreSQL with pgvector for RecallMemoryBot.");
        assertThat(topHit.distance()).isLessThan(0.0001);
        assertThat(topHit.groupId()).isEqualTo(groupA.getId());

        // Second hit is the raw message
        SearchHit secondHit = resultsA.get(1);
        assertThat(secondHit.content()).isEqualTo("Raw discussion about database selection");

        // Group B search does not see Group A data
        List<SearchHit> resultsB = semanticSearchService.search(groupB.getId(), "what database did we decide to use?", 10);
        assertThat(resultsB).hasSize(1);
        assertThat(resultsB.get(0).content()).isEqualTo("Group B decided to use MongoDB.");
    }
}

