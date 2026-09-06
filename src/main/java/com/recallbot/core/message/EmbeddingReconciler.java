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
        if (batchSize <= 0) {
            return 0;
        }

        List<UnembeddedMessage> missing = jdbcClient.sql("""
                SELECT m.id, m.group_id, m.content
                FROM messages m
                LEFT JOIN message_embeddings me ON m.id = me.message_id
                WHERE me.message_id IS NULL
                ORDER BY m.id ASC
                LIMIT :batchSize
                """)
                .param("batchSize", batchSize)
                .query((rs, rowNum) -> new UnembeddedMessage(
                        rs.getLong("id"),
                        rs.getLong("group_id"),
                        rs.getString("content")
                ))
                .list();

        if (missing.isEmpty()) {
            return 0;
        }

        log.info("Found {} un-embedded messages for reconciliation", missing.size());

        List<UnembeddedMessage> validItems = new ArrayList<>();
        List<String> textsToEmbed = new ArrayList<>();

        for (UnembeddedMessage msg : missing) {
            String normalized = textNormalizer.normalize(msg.content());
            if (!normalized.isBlank()) {
                validItems.add(msg);
                textsToEmbed.add(normalized);
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

                inserted++;
            }

            log.info("Successfully reconciled and persisted {} embeddings", inserted);
            return inserted;
        } catch (Exception e) {
            log.error("Failed to reconcile message embeddings batch: {}", e.getMessage(), e);
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
