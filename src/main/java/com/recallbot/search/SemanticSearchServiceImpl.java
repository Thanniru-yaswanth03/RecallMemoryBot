package com.recallbot.search;

import com.pgvector.PGvector;
import com.recallbot.ai.EmbeddingService;
import com.recallbot.ai.EmbeddingTextNormalizer;
import com.recallbot.config.properties.RecallProperties;
import com.recallbot.search.dto.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Implementation of {@link SemanticSearchService} utilizing PostgreSQL pgvector
 * cosine distance operator ({@code <=>}) via {@link JdbcClient}.
 * Enforces strict multi-tenant group isolation on all queries.
 */
@Service
public class SemanticSearchServiceImpl implements SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchServiceImpl.class);

    private static final String SEMANTIC_SEARCH_SQL = """
            SELECT * FROM (
                SELECT
                    m.id AS message_id,
                    m.telegram_message_id,
                    m.group_id,
                    m.user_id,
                    u.username,
                    u.first_name,
                    m.content,
                    m.sent_at,
                    (me.embedding <=> ?::vector) AS distance
                FROM message_embeddings me
                JOIN messages m ON me.message_id = m.id
                JOIN users u ON m.user_id = u.id
                WHERE me.group_id = ?

                UNION ALL

                SELECT
                    COALESCE(m.id, mem.id) AS message_id,
                    COALESCE(m.telegram_message_id, mem.id) AS telegram_message_id,
                    mem.group_id,
                    COALESCE(u.id, 0) AS user_id,
                    u.username,
                    u.first_name,
                    mem.content,
                    mem.created_at AS sent_at,
                    (mem.embedding <=> ?::vector) AS distance
                FROM memories mem
                LEFT JOIN LATERAL (
                    SELECT ms.message_id, msg.telegram_message_id, msg.id, msg.user_id
                    FROM memory_sources ms
                    JOIN messages msg ON ms.message_id = msg.id
                    WHERE ms.memory_id = mem.id
                    ORDER BY msg.sent_at DESC
                    LIMIT 1
                ) m ON true
                LEFT JOIN users u ON m.user_id = u.id
                WHERE mem.group_id = ?
                  AND mem.embedding IS NOT NULL
            ) combined
            ORDER BY distance ASC
            LIMIT ?
            """;

    private final JdbcClient jdbcClient;
    private final EmbeddingService embeddingService;
    private final EmbeddingTextNormalizer textNormalizer;
    private final RecallProperties properties;

    public SemanticSearchServiceImpl(
            JdbcClient jdbcClient,
            EmbeddingService embeddingService,
            EmbeddingTextNormalizer textNormalizer,
            RecallProperties properties
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.embeddingService = Objects.requireNonNull(embeddingService, "embeddingService must not be null");
        this.textNormalizer = Objects.requireNonNull(textNormalizer, "textNormalizer must not be null");
        this.properties = properties;
    }

    @Override
    public List<SearchHit> search(Long groupId, String queryText, int topK) {
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null for group isolation");
        }
        if (queryText == null || queryText.isBlank()) {
            log.debug("Empty or blank queryText for group_id={}, returning empty results", groupId);
            return Collections.emptyList();
        }
        if (topK <= 0) {
            log.debug("topK <= 0 ({}) for group_id={}, returning empty results", topK, groupId);
            return Collections.emptyList();
        }

        String normalizedQuery = textNormalizer.normalize(queryText);
        if (normalizedQuery.isBlank()) {
            return Collections.emptyList();
        }

        log.debug("Generating query embedding for semantic search in group_id={}, topK={}", groupId, topK);
        float[] queryVector = embeddingService.generateEmbedding(normalizedQuery);

        return searchByVector(groupId, queryVector, topK);
    }

    @Override
    public List<SearchHit> search(Long groupId, String queryText) {
        int defaultTopK = (properties != null && properties.search() != null && properties.search().maxCandidates() > 0)
                ? properties.search().maxCandidates()
                : 15;
        return search(groupId, queryText, defaultTopK);
    }

    @Override
    public List<SearchHit> searchByVector(Long groupId, float[] queryVector, int topK) {
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null for group isolation");
        }
        if (queryVector == null || queryVector.length == 0 || topK <= 0) {
            return Collections.emptyList();
        }

        int expectedDimension = embeddingService.getDimension();
        if (queryVector.length != expectedDimension) {
            throw new IllegalArgumentException(String.format(
                    "Query vector dimension mismatch: expected %d, but got %d",
                    expectedDimension, queryVector.length
            ));
        }

        PGvector pgVector = new PGvector(queryVector);

        log.debug("Executing native pgvector semantic retrieval for group_id={}, topK={}", groupId, topK);

        List<SearchHit> hits = jdbcClient.sql(SEMANTIC_SEARCH_SQL)
                .param(pgVector)
                .param(groupId)
                .param(pgVector)
                .param(groupId)
                .param(topK)
                .query((rs, rowNum) -> new SearchHit(
                        rs.getLong("message_id"),
                        rs.getLong("telegram_message_id"),
                        rs.getLong("group_id"),
                        rs.getLong("user_id"),
                        rs.getString("username"),
                        rs.getString("first_name"),
                        rs.getString("content"),
                        rs.getTimestamp("sent_at") != null ? rs.getTimestamp("sent_at").toInstant() : null,
                        rs.getDouble("distance")
                ))
                .list();

        log.debug("Retrieved {} semantic hits for group_id={}", hits.size(), groupId);
        return hits;
    }
}
