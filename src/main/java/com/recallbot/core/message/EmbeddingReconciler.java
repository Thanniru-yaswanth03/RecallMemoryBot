package com.recallbot.core.message;

import com.pgvector.PGvector;
import com.recallbot.ai.EmbeddingService;
import com.recallbot.ai.EmbeddingTextNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Background reconciler that scans for messages missing vector embeddings
 * and backfills them in batches using {@link EmbeddingService}.
 */
@Component
public class EmbeddingReconciler {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingReconciler.class);
    public static final int DEFAULT_BATCH_SIZE = 16;

    private final JdbcClient jdbcClient;
    private final EmbeddingService embeddingService;
    private final EmbeddingTextNormalizer textNormalizer;

    public EmbeddingReconciler(
            JdbcClient jdbcClient,
            EmbeddingService embeddingService,
            EmbeddingTextNormalizer textNormalizer
    ) {
        this.jdbcClient = jdbcClient;
        this.embeddingService = embeddingService;
        this.textNormalizer = textNormalizer;
    }

    public record UnembeddedMessage(Long id, Long groupId, String content) {}

    /**
     * Reconciles up to {@code batchSize} messages that currently lack embeddings.
     *
     * @param batchSize maximum number of messages to backfill
     * @return number of messages successfully backfilled
     */
    public int reconcileBatch(int batchSize) {
        return reconcileInternal(null, batchSize);
    }

    /**
     * On-demand reconciliation for a specific group chat.
     *
     * @param groupId the group ID to reconcile
     * @return number of messages successfully backfilled
     */
    public int reconcileGroup(Long groupId) {
        if (groupId == null) {
            return 0;
        }
        return reconcileInternal(groupId, DEFAULT_BATCH_SIZE);
    }

    private int reconcileInternal(Long groupId, int batchSize) {
        if (batchSize <= 0) {
            return 0;
        }

        String sql = """
                SELECT m.id, m.group_id, m.content
                FROM messages m
                LEFT JOIN message_embeddings me ON m.id = me.message_id
                WHERE (me.message_id IS NULL OR m.embedding_status IN ('PENDING', 'FAILED'))
                  AND m.embedding_attempts < 5
                """ + (groupId != null ? " AND m.group_id = :groupId " : "") + """
                ORDER BY m.id ASC
                LIMIT :batchSize
                """;

        var query = jdbcClient.sql(sql).param("batchSize", batchSize);
        if (groupId != null) {
            query = query.param("groupId", groupId);
        }

        List<UnembeddedMessage> missing = query
                .query((rs, rowNum) -> new UnembeddedMessage(
                        rs.getLong("id"),
                        rs.getLong("group_id"),
                        rs.getString("content")
                ))
                .list();

        if (missing.isEmpty()) {
            return 0;
        }

        log.info("Found {} un-embedded messages for reconciliation (groupId={})", missing.size(), groupId);

        List<UnembeddedMessage> validItems = new ArrayList<>();
        List<String> textsToEmbed = new ArrayList<>();

        for (UnembeddedMessage msg : missing) {
            String normalized = textNormalizer.normalize(msg.content());
            if (!normalized.isBlank()) {
                validItems.add(msg);
                textsToEmbed.add(normalized);
            } else {
                // Permanently mark blank message as SKIPPED so it does not block subsequent reconciliation runs
                jdbcClient.sql("""
                        UPDATE messages
                        SET embedding_status = 'SKIPPED',
                            embedding_attempts = embedding_attempts + 1,
                            last_attempt_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """)
                        .param(msg.id())
                        .update();
            }
        }

        if (validItems.isEmpty()) {
            return 0;
        }

        try {
            List<float[]> vectors = embeddingService.generateEmbeddings(textsToEmbed);
            if (vectors.size() != validItems.size()) {
                throw new IllegalStateException(String.format(
                        "Batch embedding size mismatch: expected %d vectors, got %d",
                        validItems.size(), vectors.size()
                ));
            }

            String modelName = embeddingService.getModelName();
            int inserted = 0;

            for (int i = 0; i < validItems.size(); i++) {
                UnembeddedMessage msg = validItems.get(i);
                float[] vector = vectors.get(i);
                PGvector pgVector = new PGvector(vector);

                jdbcClient.sql("""
                        INSERT INTO message_embeddings (message_id, group_id, embedding, model_name, created_at)
                        VALUES (?, ?, ?::vector, ?, CURRENT_TIMESTAMP)
                        ON CONFLICT (message_id) DO UPDATE SET
                            embedding = EXCLUDED.embedding,
                            model_name = EXCLUDED.model_name,
                            created_at = CURRENT_TIMESTAMP
                        """)
                        .param(msg.id())
                        .param(msg.groupId())
                        .param(pgVector)
                        .param(modelName)
                        .update();

                jdbcClient.sql("""
                        UPDATE messages
                        SET embedding_status = 'EMBEDDED',
                            embedding_error = NULL,
                            embedding_attempts = embedding_attempts + 1,
                            last_attempt_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """)
                        .param(msg.id())
                        .update();

                inserted++;
            }

            log.info("Successfully reconciled and persisted {} embeddings", inserted);
            return inserted;
        } catch (Exception e) {
            log.error("Failed to reconcile message embeddings batch: {}", e.getMessage(), e);
            for (UnembeddedMessage msg : validItems) {
                try {
                    jdbcClient.sql("""
                            UPDATE messages
                            SET embedding_status = 'FAILED',
                                embedding_error = ?,
                                embedding_attempts = embedding_attempts + 1,
                                last_attempt_at = CURRENT_TIMESTAMP
                            WHERE id = ?
                            """)
                            .param(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                            .param(msg.id())
                            .update();
                } catch (Exception ignored) {
                }
            }
            return 0;
        }
    }

    @Scheduled(fixedDelayString = "${recall.ai.reconcile-interval-ms:300000}", initialDelay = 60000)
    public void scheduledReconciliation() {
        try {
            reconcileBatch(DEFAULT_BATCH_SIZE);
        } catch (Exception e) {
            log.warn("Scheduled embedding reconciliation encountered an error: {}", e.getMessage());
        }
    }
}
