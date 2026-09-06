package com.recallbot.core.message;

import com.recallbot.ai.EmbeddingService;
import com.recallbot.ai.EmbeddingTextNormalizer;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.message.event.MessagePersistedEvent;
import com.recallbot.core.user.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageEmbeddingListenerTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private MessageEmbeddingRepository messageEmbeddingRepository;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private EmbeddingTextNormalizer textNormalizer;

    @Mock
    private JdbcClient jdbcClient;

    private MessageEmbeddingListener listener;

    @BeforeEach
    void setUp() {
        listener = new MessageEmbeddingListener(
                messageRepository,
                messageEmbeddingRepository,
                embeddingService,
                textNormalizer,
                jdbcClient
        );
    }

    @Test
    @DisplayName("Successfully processes MessagePersistedEvent and persists vector embedding")
    void processesNewMessage() {
        Long messageId = 100L;
        Long groupId = 200L;
        MessagePersistedEvent event = new MessagePersistedEvent(messageId, groupId, false);

        GroupEntity group = new GroupEntity(-100123L, "Test Group");
        group.setId(groupId);
        UserEntity user = new UserEntity(456L, "john", "John", null);
        user.setId(1L);
        MessageEntity message = new MessageEntity(group, user, 1001L, "Hello world", Instant.now());
        message.setId(messageId);

        when(messageEmbeddingRepository.findByMessageId(messageId)).thenReturn(Optional.empty());
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
        when(textNormalizer.normalize("Hello world")).thenReturn("Hello world");

        float[] vector = new float[1536];
        when(embeddingService.generateEmbedding("Hello world")).thenReturn(vector);
        when(embeddingService.getModelName()).thenReturn("openai/text-embedding-3-small");

        // Mock JdbcClient fluent API for saving embedding
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(any())).thenReturn(statementSpec);
        when(statementSpec.update()).thenReturn(1);

        listener.onMessagePersisted(event);

        verify(embeddingService).generateEmbedding("Hello world");
        verify(statementSpec).update();
    }

    @Test
    @DisplayName("Skips vectorization if embedding already exists and isEdit is false")
    void skipsIfAlreadyExists() {
        Long messageId = 100L;
        Long groupId = 200L;
        MessagePersistedEvent event = new MessagePersistedEvent(messageId, groupId, false);

        MessageEmbeddingEntity existing = new MessageEmbeddingEntity();
        when(messageEmbeddingRepository.findByMessageId(messageId)).thenReturn(Optional.of(existing));

        listener.onMessagePersisted(event);

        verify(messageRepository, never()).findById(anyLong());
        verify(embeddingService, never()).generateEmbedding(anyString());
    }

    @Test
    @DisplayName("Re-vectorizes if isEdit is true even if embedding already exists")
    void reVectorizesOnEdit() {
        Long messageId = 100L;
        Long groupId = 200L;
        MessagePersistedEvent event = new MessagePersistedEvent(messageId, groupId, true);

        GroupEntity group = new GroupEntity(-100123L, "Test Group");
        group.setId(groupId);
        UserEntity user = new UserEntity(456L, "john", "John", null);
        user.setId(1L);
        MessageEntity message = new MessageEntity(group, user, 1001L, "Edited content", Instant.now());
        message.setId(messageId);

        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
        when(textNormalizer.normalize("Edited content")).thenReturn("Edited content");

        float[] vector = new float[1536];
        when(embeddingService.generateEmbedding("Edited content")).thenReturn(vector);
        when(embeddingService.getModelName()).thenReturn("openai/text-embedding-3-small");

        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(any())).thenReturn(statementSpec);
        when(statementSpec.update()).thenReturn(1);

        listener.onMessagePersisted(event);

        verify(embeddingService).generateEmbedding("Edited content");
        verify(statementSpec).update();
    }

    @Test
    @DisplayName("Gracefully handles and logs exceptions from EmbeddingService without crashing")
    void handlesExceptionGracefully() {
        Long messageId = 100L;
        Long groupId = 200L;
        MessagePersistedEvent event = new MessagePersistedEvent(messageId, groupId, false);

        GroupEntity group = new GroupEntity(-100123L, "Test Group");
        group.setId(groupId);
        UserEntity user = new UserEntity(456L, "john", "John", null);
        user.setId(1L);
        MessageEntity message = new MessageEntity(group, user, 1001L, "Content", Instant.now());
        message.setId(messageId);

        when(messageEmbeddingRepository.findByMessageId(messageId)).thenReturn(Optional.empty());
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
        when(textNormalizer.normalize("Content")).thenReturn("Content");
        when(embeddingService.generateEmbedding("Content")).thenThrow(new IllegalStateException("OpenRouter 500 error"));

        // Must not throw uncaught exception
        listener.onMessagePersisted(event);

        verify(embeddingService).generateEmbedding("Content");
        verify(jdbcClient, never()).sql(anyString());
    }
}
