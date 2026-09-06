package com.recallbot.persistence;

import com.pgvector.PGvector;
import com.recallbot.ai.EmbeddingService;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupRepository;
import com.recallbot.core.message.MessageEmbeddingEntity;
import com.recallbot.core.message.MessageEmbeddingListener;
import com.recallbot.core.message.MessageEmbeddingRepository;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.core.message.event.MessagePersistedEvent;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
class EmbeddingPipelineIT extends BasePostgresIntegrationTest {

    private static final int DIMENSION = 1536;
    private static final String MODEL = "openai/text-embedding-3-small";

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageEmbeddingRepository messageEmbeddingRepository;

    @Autowired
    private MessageEmbeddingListener messageEmbeddingListener;

    @Autowired
    private JdbcClient jdbcClient;

    @MockBean
    private EmbeddingService embeddingService;

    @org.junit.jupiter.api.BeforeEach
    void cleanUp() {
        jdbcClient.sql("DELETE FROM message_embeddings").update();
        jdbcClient.sql("DELETE FROM memory_sources").update();
        jdbcClient.sql("DELETE FROM memories").update();
        jdbcClient.sql("DELETE FROM messages").update();
        jdbcClient.sql("DELETE FROM group_memberships").update();
        jdbcClient.sql("DELETE FROM groups").update();
        jdbcClient.sql("DELETE FROM users").update();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        cleanUp();
    }

    private float[] createTestVector(float base) {
        float[] v = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            v[i] = base + (float) i / 10000.0f;
        }
        return v;
    }

    @Test
    @DisplayName("End-to-end vector pipeline: persists 1024-d embedding and queries via cosine distance")
    void endToEndEmbeddingPipeline() throws Exception {
        GroupEntity group = groupRepository.save(new GroupEntity(-10088990011L, "Pipeline Test Group"));
        UserEntity user = userRepository.save(new UserEntity(99887766L, "alice", "Alice", null));
        MessageEntity message = messageRepository.save(new MessageEntity(group, user, 555L, "Pipeline message test", Instant.now()));

        float[] mockVector = createTestVector(0.05f);
        when(embeddingService.generateEmbedding(anyString())).thenReturn(mockVector);
        when(embeddingService.getModelName()).thenReturn(MODEL);
        when(embeddingService.getDimension()).thenReturn(DIMENSION);

        MessagePersistedEvent event = new MessagePersistedEvent(message.getId(), group.getId(), false);
        messageEmbeddingListener.onMessagePersisted(event);

        Optional<MessageEmbeddingEntity> savedOpt = Optional.empty();
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            savedOpt = messageEmbeddingRepository.findByMessageId(message.getId());
            if (savedOpt.isPresent()) {
                break;
            }
            Thread.sleep(50);
        }

        assertThat(savedOpt).isPresent();

        MessageEmbeddingEntity saved = savedOpt.get();
        assertThat(saved.getModelName()).isEqualTo(MODEL);
        assertThat(saved.getEmbedding()).hasSize(DIMENSION);
        assertThat(saved.getGroup().getId()).isEqualTo(group.getId());

        // Verify native pgvector cosine distance query
        PGvector queryVector = new PGvector(mockVector);
        Double distance = jdbcClient.sql("""
                SELECT embedding <=> ?::vector AS dist
                FROM message_embeddings
                WHERE message_id = ?
                """)
                .param(queryVector)
                .param(message.getId())
                .query(Double.class)
                .single();

        assertThat(distance).isNotNull();
        assertThat(distance).isLessThan(0.0001);
    }
}
