package com.recallbot.memory;

import com.recallbot.ai.EmbeddingService;
import com.recallbot.config.properties.RecallProperties;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupRepository;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.core.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock
    private MemoryRepository memoryRepository;
    @Mock
    private MemorySourceRepository memorySourceRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private GroupRepository groupRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private JdbcClient jdbcClient;
    @Mock
    private JdbcClient.StatementSpec statementSpec;

    private RecallProperties properties;
    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        properties = new RecallProperties(
                new RecallProperties.Telegram("webhook", "recall_bot", "token", "secret", null),
                new RecallProperties.Ai("key", "chat", "openai/text-embedding-3-small", 1536, 30, 800, 0.2),
                new RecallProperties.Search(15, 60, 2500),
                new RecallProperties.RateLimit(3, 10)
        );

        memoryService = new MemoryService(
                memoryRepository,
                memorySourceRepository,
                messageRepository,
                groupRepository,
                userRepository,
                embeddingService,
                properties,
                jdbcClient
        );
    }

    @Test
    @DisplayName("recordExplicitMemory persists memory with 1536-d vector and links source message")
    void recordExplicitMemorySuccess() {
        GroupEntity group = new GroupEntity(-1001234567890L, "Architecture Group");
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));

        float[] mockVector = new float[1536];
        mockVector[0] = 0.5f;
        when(embeddingService.generateEmbedding("We decided to use PostgreSQL 16")).thenReturn(mockVector);

        MessageEntity message = new MessageEntity();
        when(messageRepository.findByGroupIdAndTelegramMessageId(1L, 501L)).thenReturn(Optional.of(message));

        when(memoryRepository.save(any(MemoryEntity.class))).thenAnswer(invocation -> {
            MemoryEntity entity = invocation.getArgument(0);
            return entity;
        });

        MemoryEntity result = memoryService.recordExplicitMemory(1L, 10L, 501L, "We decided to use PostgreSQL 16");

        assertThat(result).isNotNull();
        assertThat(result.getContent()).isEqualTo("We decided to use PostgreSQL 16");
        assertThat(result.getMemoryType()).isEqualTo(MemoryType.DECISION);
        assertThat(result.getConfidence()).isEqualByComparingTo("1.00");
        assertThat(result.getEmbedding()).hasSize(1536);
        assertThat(result.getModelName()).isEqualTo("openai/text-embedding-3-small");

        verify(memoryRepository).save(any(MemoryEntity.class));
        verify(memorySourceRepository).save(any(MemorySourceEntity.class));
    }

    @Test
    @DisplayName("recordExplicitMemory throws when vector dimension mismatches configured dimension")
    void recordExplicitMemoryDimensionMismatchThrows() {
        GroupEntity group = new GroupEntity(-1001234567890L, "Architecture Group");
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));

        // Returns 768 instead of expected 1536
        float[] badVector = new float[768];
        when(embeddingService.generateEmbedding(anyString())).thenReturn(badVector);

        assertThatThrownBy(() -> memoryService.recordExplicitMemory(1L, 10L, 501L, "Fact"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected 1536, got 768");
    }

    @Test
    @DisplayName("recordDerivedMemory links multiple source messages")
    void recordDerivedMemoryMultipleSources() {
        GroupEntity group = new GroupEntity(-1001234567890L, "Architecture Group");
        org.springframework.test.util.ReflectionTestUtils.setField(group, "id", 1L);
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group));

        float[] mockVector = new float[1536];
        when(embeddingService.generateEmbedding(anyString())).thenReturn(mockVector);

        MessageEntity msg1 = new MessageEntity();
        msg1.setGroup(group);
        MessageEntity msg2 = new MessageEntity();
        msg2.setGroup(group);
        when(messageRepository.findById(101L)).thenReturn(Optional.of(msg1));
        when(messageRepository.findById(102L)).thenReturn(Optional.of(msg2));

        when(memoryRepository.save(any(MemoryEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MemoryEntity derived = memoryService.recordDerivedMemory(
                1L,
                MemoryType.DECISION,
                "Team agreed on PostgreSQL",
                new BigDecimal("0.95"),
                List.of(101L, 102L)
        );

        assertThat(derived).isNotNull();
        assertThat(derived.getMemoryType()).isEqualTo(MemoryType.DECISION);
        verify(memorySourceRepository, org.mockito.Mockito.times(2)).save(any(MemorySourceEntity.class));
    }

    @Test
    @DisplayName("cleanupOrphanedMemories executes SQL delete for memories without sources")
    void cleanupOrphanedMemoriesExecutesSql() {
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param("groupId", 1L)).thenReturn(statementSpec);
        when(statementSpec.update()).thenReturn(3);

        int count = memoryService.cleanupOrphanedMemories(1L);

        assertThat(count).isEqualTo(3);
        verify(jdbcClient).sql(anyString());
    }
}
