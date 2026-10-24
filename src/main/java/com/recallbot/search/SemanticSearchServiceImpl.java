package com.recallbot.search;

import com.pgvector.PGvector;
import com.recallbot.ai.EmbeddingService;
import com.recallbot.ai.EmbeddingTextNormalizer;
import com.recallbot.config.properties.RecallProperties;
import com.recallbot.search.dto.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Implementation of {@link SemanticSearchService} utilizing:
 * 1. PostgreSQL pgvector cosine similarity retrieval.
 * 2. Full-text search (FTS) against pre-indexed tsv_content.
 * 3. Exact matching for names (e.g. yo_siri), dates, task IDs, numbers, and cross-lingual tokens.
 * 4. Explicit memory prioritization.
 * 5. Reciprocal Rank Fusion (RRF) to combine disparate candidate rankings.
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
    private final MultilingualQueryProcessor queryProcessor;

    public SemanticSearchServiceImpl(
            JdbcClient jdbcClient,
            EmbeddingService embeddingService,
            EmbeddingTextNormalizer textNormalizer,
            RecallProperties properties
    ) {
        this(jdbcClient, embeddingService, textNormalizer, properties, new MultilingualQueryProcessorImpl());
    }

    @Autowired
    public SemanticSearchServiceImpl(
            JdbcClient jdbcClient,
            EmbeddingService embeddingService,
            EmbeddingTextNormalizer textNormalizer,
            RecallProperties properties,
            MultilingualQueryProcessor queryProcessor
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.embeddingService = Objects.requireNonNull(embeddingService, "embeddingService must not be null");
        this.textNormalizer = Objects.requireNonNull(textNormalizer, "textNormalizer must not be null");
        this.properties = properties;
        this.queryProcessor = queryProcessor != null ? queryProcessor : new MultilingualQueryProcessorImpl();
    }

    @Override
    public List<SearchHit> search(Long groupId, String queryText, int topK) {
        return searchHybrid(groupId, queryText, topK, Collections.emptySet());
    }

    @Override
    public List<SearchHit> search(Long groupId, String queryText) {
        int defaultTopK = (properties != null && properties.search() != null && properties.search().maxCandidates() > 0)
                ? properties.search().maxCandidates()
                : 15;
        return search(groupId, queryText, defaultTopK);
    }

    @Override
    public List<SearchHit> searchHybrid(Long groupId, String queryText, int topK, Set<Long> excludedMessageIds) {
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null for group isolation");
        }
        if (queryText == null || queryText.isBlank() || topK <= 0) {
            return Collections.emptyList();
        }

        String normalizedQuery = textNormalizer != null ? textNormalizer.normalize(queryText) : queryText;
        MultilingualQueryProcessor.ProcessedQuery processed = queryProcessor.process(normalizedQuery);

        // For multi-item queries (e.g. all tasks), increase candidate exploration headroom
        int candidateBudget = processed.isMultiItemRequest() ? Math.max(topK * 2, 25) : topK;
        int rrfK = (properties != null && properties.search() != null && properties.search().rrfK() > 0)
                ? properties.search().rrfK()
                : 60;

        Map<Long, HitScore> hitScores = new LinkedHashMap<>();

        // 1. Vector Semantic Search
        try {
            float[] queryVector = embeddingService.generateEmbedding(normalizedQuery);
            List<SearchHit> vectorHits = searchByVector(groupId, queryVector, candidateBudget);
            for (int rank = 0; rank < vectorHits.size(); rank++) {
                SearchHit hit = vectorHits.get(rank);
                double rrfContribution = 1.0 / (rrfK + (rank + 1));
                hitScores.compute(hit.messageId(), (k, v) -> v == null
                        ? new HitScore(hit, rrfContribution, isMemory(hit))
                        : v.addScore(rrfContribution));
            }
        } catch (Exception e) {
            log.warn("[Group #{}] Semantic embedding search failed, continuing with keyword & FTS fallback: {}",
                    groupId, e.getMessage());
        }

        // 2. Full-Text Search (FTS on tsv_content)
        if (processed.ftsQuery() != null && !processed.ftsQuery().isBlank()) {
            try {
                List<SearchHit> ftsHits = executeFtsSearch(groupId, processed.ftsQuery(), candidateBudget);
                for (int rank = 0; rank < ftsHits.size(); rank++) {
                    SearchHit hit = ftsHits.get(rank);
                    double rrfContribution = 1.0 / (rrfK + (rank + 1));
                    hitScores.compute(hit.messageId(), (k, v) -> v == null
                            ? new HitScore(hit, rrfContribution, false)
                            : v.addScore(rrfContribution));
                }
            } catch (Exception e) {
                log.debug("[Group #{}] FTS query failed: {}", groupId, e.getMessage());
            }
        }

        // 3. Exact Matching (for identifiers like yo_siri, dates, task IDs, numbers, and cross-lingual tokens)
        Set<String> searchTerms = new LinkedHashSet<>();
        searchTerms.addAll(processed.exactKeywords());
        searchTerms.addAll(processed.crossLingualTerms());

        if (!searchTerms.isEmpty()) {
            try {
                List<SearchHit> exactHits = executeExactKeywordSearch(groupId, searchTerms, candidateBudget);
                for (int rank = 0; rank < exactHits.size(); rank++) {
                    SearchHit hit = exactHits.get(rank);
                    double rrfContribution = 1.0 / (rrfK + (rank + 1));
                    hitScores.compute(hit.messageId(), (k, v) -> v == null
                            ? new HitScore(hit, rrfContribution, false)
                            : v.addScore(rrfContribution));
                }
            } catch (Exception e) {
                log.debug("[Group #{}] Exact keyword search failed: {}", groupId, e.getMessage());
            }
        }

        // 4. Boost explicit memories if present in hit scores
        for (HitScore hs : hitScores.values()) {
            if (hs.isMemory) {
                hs.score += 0.05; // Explicit memory priority boost
            }
        }

        // 5. Exclude already retrieved message IDs (for "what else?" follow-up queries)
        Set<Long> exclusions = (excludedMessageIds != null) ? excludedMessageIds : Collections.emptySet();

        // 6. Deduplicate near-identical content and order by RRF score descending
        List<SearchHit> finalHits = hitScores.values().stream()
                .filter(hs -> hs.hit.telegramMessageId() == null || !exclusions.contains(hs.hit.telegramMessageId()))
                .sorted(Comparator.comparingDouble(HitScore::getScore).reversed())
                .map(hs -> {
                    // Normalize distance for SearchHit (distance = 1.0 - normalized score)
                    double normDist = Math.max(0.0, 1.0 - (hs.score * 10.0));
                    return new SearchHit(
                            hs.hit.messageId(),
                            hs.hit.telegramMessageId(),
                            hs.hit.groupId(),
                            hs.hit.userId(),
                            hs.hit.username(),
                            hs.hit.firstName(),
                            hs.hit.content(),
                            hs.hit.sentAt(),
                            normDist
                    );
                })
                .limit(topK)
                .toList();

        log.debug("[Group #{}] Hybrid retrieval finished: candidates={}, returned={}",
                groupId, hitScores.size(), finalHits.size());

        return finalHits;
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

        return jdbcClient.sql(SEMANTIC_SEARCH_SQL)
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
    }

    private List<SearchHit> executeFtsSearch(Long groupId, String ftsQuery, int limit) {
        String sql = """
                SELECT m.id AS message_id, m.telegram_message_id, m.group_id, m.user_id,
                       u.username, u.first_name, m.content, m.sent_at,
                       ts_rank_cd(m.tsv_content, plainto_tsquery('english', ?)) AS rank_score
                FROM messages m
                JOIN users u ON m.user_id = u.id
                WHERE m.group_id = ?
                  AND m.tsv_content @@ plainto_tsquery('english', ?)
                ORDER BY rank_score DESC
                LIMIT ?
                """;

        return jdbcClient.sql(sql)
                .param(ftsQuery)
                .param(groupId)
                .param(ftsQuery)
                .param(limit)
                .query((rs, rowNum) -> new SearchHit(
                        rs.getLong("message_id"),
                        rs.getLong("telegram_message_id"),
                        rs.getLong("group_id"),
                        rs.getLong("user_id"),
                        rs.getString("username"),
                        rs.getString("first_name"),
                        rs.getString("content"),
                        rs.getTimestamp("sent_at") != null ? rs.getTimestamp("sent_at").toInstant() : null,
                        0.5
                ))
                .list();
    }

    private List<SearchHit> executeExactKeywordSearch(Long groupId, Set<String> terms, int limit) {
        if (terms == null || terms.isEmpty()) {
            return Collections.emptyList();
        }

        StringBuilder whereClause = new StringBuilder();
        List<Object> paramList = new ArrayList<>();
        paramList.add(groupId);

        int idx = 0;
        for (String term : terms) {
            if (term.length() < 2) continue;
            if (idx > 0) whereClause.append(" OR ");
            whereClause.append("LOWER(m.content) LIKE ?");
            paramList.add("%" + term.toLowerCase() + "%");
            idx++;
        }

        if (idx == 0) {
            return Collections.emptyList();
        }

        paramList.add(limit);

        String sql = "SELECT m.id AS message_id, m.telegram_message_id, m.group_id, m.user_id, "
                + "u.username, u.first_name, m.content, m.sent_at "
                + "FROM messages m "
                + "JOIN users u ON m.user_id = u.id "
                + "WHERE m.group_id = ? "
                + "  AND (" + whereClause + ") "
                + "ORDER BY m.sent_at DESC "
                + "LIMIT ?";

        var client = jdbcClient.sql(sql);
        for (Object param : paramList) {
            client = client.param(param);
        }

        return client.query((rs, rowNum) -> new SearchHit(
                rs.getLong("message_id"),
                rs.getLong("telegram_message_id"),
                rs.getLong("group_id"),
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("first_name"),
                rs.getString("content"),
                rs.getTimestamp("sent_at") != null ? rs.getTimestamp("sent_at").toInstant() : null,
                0.2
        )).list();
    }

    private boolean isMemory(SearchHit hit) {
        return hit.userId() != null && hit.userId() == 0;
    }

    private static class HitScore {
        final SearchHit hit;
        double score;
        final boolean isMemory;

        HitScore(SearchHit hit, double score, boolean isMemory) {
            this.hit = hit;
            this.score = score;
            this.isMemory = isMemory;
        }

        HitScore addScore(double additional) {
            this.score += additional;
            return this;
        }

        double getScore() {
            return score;
        }
    }
}
