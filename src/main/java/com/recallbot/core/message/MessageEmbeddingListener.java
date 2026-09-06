package com.recallbot.core.message;

import com.pgvector.PGvector;
import com.recallbot.ai.EmbeddingService;
import com.recallbot.ai.EmbeddingTextNormalizer;
import com.recallbot.config.AsyncConfig;
import com.recallbot.core.message.event.MessagePersistedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * Asynchronous event consumer that vectorizes persisted Telegram messages and stores
 * dense embeddings into PostgreSQL {@code message_embeddings} table.
 */
@Component
public class MessageEmbeddingListener {

    private static final Logger log = LoggerFactory.getLogger(MessageEmbeddingListener.class);

    private final MessageRepository messageRepository;
    private final MessageEmbeddingRepository messageEmbeddingRepository;
    private final EmbeddingService embeddingService;
    private final EmbeddingTextNormalizer textNormalizer;
    private final JdbcClient jdbcClient;

    public MessageEmbeddingListener(
            MessageRepository messageRepository,
            MessageEmbeddingRepository messageEmbeddingRepository,
            EmbeddingService embeddingService,
            EmbeddingTextNormalizer textNormalizer,
            JdbcClient jdbcClient
    ) {
        this.messageRepository = messageRepository;
        this.messageEmbeddingRepository = messageEmbeddingRepository;
        this.embeddingService = embeddingService;
        this.textNormalizer = textNormalizer;
        this.jdbcClient = jdbcClient;
    }

    @Async(AsyncConfig.TELEGRAM_TASK_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessagePersisted(MessagePersistedEvent event) {
        if (event == null || event.messageId() == null) {
            return;
        }

        Long messageId = event.messageId();
        Long groupId = event.groupId();

        log.debug("Received MessagePersistedEvent for message_id={}, group_id={}, isEdit={}",
                messageId, groupId, event.isEdit());

        try {
            // Idempotency: skip if already vectorized unless this is an edit
            if (!event.isEdit() && messageEmbeddingRepository.findByMessageId(messageId).isPresent()) {
                log.debug("Embedding already exists for message_id={}; skipping vectorization", messageId);
                return;
            }

            Optional<MessageEntity> messageOpt = messageRepository.findById(messageId);
            if (messageOpt.isEmpty()) {
                log.warn("Message with id={} not found for vectorization", messageId);
                return;
            }

            MessageEntity message = messageOpt.get();
            String normalizedContent = textNormalizer.normalize(message.getContent());
            if (normalizedContent.isBlank()) {
                log.debug("Message id={} normalized content is blank; skipping vectorization", messageId);
                return;
            }

            float[] vector = embeddingService.generateEmbedding(normalizedContent);
            saveEmbedding(messageId, groupId, vector, embeddingService.getModelName());

            log.info("Successfully persisted embedding for message_id={}, group_id={}, model={}",
                    messageId, groupId, embeddingService.getModelName());
        } catch (Exception e) {
            log.error("Failed to generate or persist embedding for message_id={}, group_id={}: {}",
                    messageId, groupId, e.getMessage(), e);
        }
    }

    public void saveEmbedding(Long messageId, Long groupId, float[] vector, String modelName) {
        PGvector pgVector = new PGvector(vector);
        jdbcClient.sql("""
                INSERT INTO message_embeddings (message_id, group_id, embedding, model_name, created_at)
                VALUES (?, ?, ?::vector, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (message_id) DO UPDATE SET
                    embedding = EXCLUDED.embedding,
                    model_name = EXCLUDED.model_name,
                    created_at = CURRENT_TIMESTAMP
                """)
                .param(messageId)
                .param(groupId)
                .param(pgVector)
                .param(modelName)
                .update();
    }
}
