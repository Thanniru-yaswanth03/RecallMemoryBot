package com.recallbot.persistence;

import com.pgvector.PGvector;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupRepository;
import com.recallbot.core.message.MessageEmbeddingEntity;
import com.recallbot.core.message.MessageEmbeddingRepository;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserRepository;
import com.recallbot.memory.MemoryEntity;
import com.recallbot.memory.MemoryRepository;
import com.recallbot.memory.MemorySourceEntity;
import com.recallbot.memory.MemorySourceRepository;
import com.recallbot.memory.MemoryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class MessageEmbeddingPersistenceIT extends BasePostgresIntegrationTest {

    private static final int REQUIRED_DIMENSION = 1536;
    private static final String MODEL_NAME = "openai/text-embedding-3-small";

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageEmbeddingRepository messageEmbeddingRepository;

    @Autowired
    private MemoryRepository memoryRepository;

    @Autowired
    private MemorySourceRepository memorySourceRepository;

    @Autowired
    private JdbcClient jdbcClient;

    private float[] createDeterministicVector(int dimension, float baseValue) {
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = baseValue + (float) i / 10000.0f;
        }
        return vector;
    }

    @Test
    @Transactional
    @DisplayName("Persist and retrieve 1024-dimensional message embedding")
    void persistAndRetrieve1024DimensionalVector() {
        GroupEntity group = groupRepository.save(new GroupEntity(-1002233445566L, "Vector Test Group"));
        UserEntity user = userRepository.save(new UserEntity(223344556L, "david_dev", "David", null));
        MessageEntity message = messageRepository.save(new MessageEntity(group, user, 888L, "Vector test message", Instant.now()));

        float[] originalVector = createDeterministicVector(REQUIRED_DIMENSION, 0.05f);

        MessageEmbeddingEntity embeddingEntity = new MessageEmbeddingEntity(message, group, originalVector, MODEL_NAME);
        messageEmbeddingRepository.saveAndFlush(embeddingEntity);

        Optional<MessageEmbeddingEntity> loadedOpt = messageEmbeddingRepository.findByMessageId(message.getId());
        assertThat(loadedOpt).isPresent();

        MessageEmbeddingEntity loaded = loadedOpt.get();
        assertThat(loaded.getModelName()).isEqualTo(MODEL_NAME);
        assertThat(loaded.getEmbedding()).isNotNull();
        assertThat(loaded.getEmbedding()).hasSize(REQUIRED_DIMENSION);
        assertThat(loaded.getEmbedding()[0]).isEqualTo(originalVector[0]);
        assertThat(loaded.getEmbedding()[REQUIRED_DIMENSION - 1]).isEqualTo(originalVector[REQUIRED_DIMENSION - 1]);
    }

    @Test
    @Transactional
    @DisplayName("Compute cosine distance (<=>) using native pgvector operator in JdbcClient")
    void computeCosineDistance() {
        GroupEntity group = groupRepository.save(new GroupEntity(-1003344556677L, "Cosine Distance Group"));
        UserEntity user = userRepository.save(new UserEntity(334455667L, "eva_dev", "Eva", null));
        MessageEntity message = messageRepository.save(new MessageEntity(group, user, 999L, "Similar message", Instant.now()));

        float[] vector = createDeterministicVector(REQUIRED_DIMENSION, 0.1f);
        MessageEmbeddingEntity embeddingEntity = new MessageEmbeddingEntity(message, group, vector, MODEL_NAME);
        messageEmbeddingRepository.saveAndFlush(embeddingEntity);

        PGvector queryVector = new PGvector(vector);

        Double distance = jdbcClient.sql("""
                SELECT embedding <=> ?::vector AS distance
                FROM message_embeddings
                WHERE message_id = ?
                """)
                .param(queryVector)
                .param(message.getId())
                .query(Double.class)
                .single();

        // Identical vector cosine distance should be ~0.0
        assertThat(distance).isNotNull();
        assertThat(distance).isLessThan(0.0001);
    }

    @Test
    @Transactional
    @DisplayName("Enforce 1024-dimensional column constraint by rejecting mismatched dimensions")
    void enforceDimensionConstraint() {
        GroupEntity group = groupRepository.save(new GroupEntity(-1004455667788L, "Dimension Test Group"));
        UserEntity user = userRepository.save(new UserEntity(445566778L, "frank_dev", "Frank", null));
        MessageEntity message = messageRepository.save(new MessageEntity(group, user, 1010L, "Mismatch test", Instant.now()));

        // Try inserting a 512-dimension vector into the 1024-dimension column
        float[] invalidDimensionVector = createDeterministicVector(512, 0.2f);
        PGvector pgVector512 = new PGvector(invalidDimensionVector);

        assertThatThrownBy(() -> {
            jdbcClient.sql("""
                    INSERT INTO message_embeddings (message_id, group_id, embedding, model_name, created_at)
                    VALUES (?, ?, ?::vector, ?, CURRENT_TIMESTAMP)
                    """)
                    .param(message.getId())
                    .param(group.getId())
                    .param(pgVector512)
                    .param(MODEL_NAME)
                    .update();
        }).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("expected 1536 dimensions, not 512");
    }

    @Test
    @Transactional
    @DisplayName("Persist memory and verify memory_sources join table and cascade behavior")
    void persistMemoryAndSources() {
        GroupEntity group = groupRepository.save(new GroupEntity(-1005566778899L, "Memory Provenance Group"));
        UserEntity user = userRepository.save(new UserEntity(556677889L, "grace_dev", "Grace", null));
        MessageEntity message1 = messageRepository.save(new MessageEntity(group, user, 11L, "Discussion 1", Instant.now()));
        MessageEntity message2 = messageRepository.save(new MessageEntity(group, user, 12L, "Discussion 2", Instant.now()));

        MemoryEntity memory = new MemoryEntity(
                group,
                MemoryType.DECISION,
                "Team agreed to use liquid/lfm2.5-embedding-350m with 1024 dimensions.",
                new BigDecimal("0.95")
        );
        memory.setEmbedding(createDeterministicVector(REQUIRED_DIMENSION, 0.3f));
        memory.setModelName(MODEL_NAME);
        MemoryEntity savedMemory = memoryRepository.saveAndFlush(memory);

        MemorySourceEntity source1 = new MemorySourceEntity(savedMemory, message1);
        MemorySourceEntity source2 = new MemorySourceEntity(savedMemory, message2);
        memorySourceRepository.saveAllAndFlush(List.of(source1, source2));

        List<MemorySourceEntity> sources = memorySourceRepository.findByMemoryId(savedMemory.getId());
        assertThat(sources).hasSize(2);

        // Deleting message1 should cascade-delete source1 but keep memory and source2
        memorySourceRepository.delete(source1);
        memorySourceRepository.flush();

        List<MemorySourceEntity> remainingSources = memorySourceRepository.findByMemoryId(savedMemory.getId());
        assertThat(remainingSources).hasSize(1);
        assertThat(remainingSources.get(0).getMessage().getId()).isEqualTo(message2.getId());
    }
}
