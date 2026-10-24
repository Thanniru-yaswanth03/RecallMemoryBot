package com.recallbot.search;

import com.recallbot.ai.citation.CitationValidator;
import com.recallbot.search.dto.SearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 11: Quantitative Retrieval Quality Benchmark.
 * Measures Recall@k, Precision@k, MRR, Multi-Item Recall, Cross-Language Success,
 * and Citation Validity across a standardized evaluation dataset.
 */
class RetrievalQualityBenchmarkTest {

    private MultilingualQueryProcessor queryProcessor;
    private ConversationContextService contextService;
    private CitationValidator citationValidator;

    public record BenchmarkCase(
            String query,
            String queryLanguage,
            List<Long> groundTruthMessageIds,
            boolean isMultiItem,
            boolean isCrossLingual
    ) {}

    public record GroundTruthDoc(
            Long messageId,
            Long telegramMessageId,
            String content,
            String language
    ) {}

    private List<GroundTruthDoc> corpus;
    private List<BenchmarkCase> evalSet;

    @BeforeEach
    void setUp() {
        queryProcessor = new MultilingualQueryProcessorImpl();
        contextService = new ConversationContextService();
        citationValidator = new CitationValidator();

        // 1. Synthetic Corpus representing realistic Telegram group chat
        corpus = List.of(
                new GroundTruthDoc(1L, 101L, "Task 1: Setup PostgreSQL with pgvector for vector storage", "en"),
                new GroundTruthDoc(2L, 102L, "Task 2: Configure Telegram webhook secret token authentication", "en"),
                new GroundTruthDoc(3L, 103L, "Task 3: Integrate OpenRouter chat and embedding clients", "en"),
                new GroundTruthDoc(4L, 104L, "Architecture decision: We chose Spring Boot 3.3.4 with Java 21", "en"),
                new GroundTruthDoc(5L, 105L, "Sprint meeting scheduled for tomorrow at 10:00 AM UTC", "en"),
                new GroundTruthDoc(6L, 106L, "हमारा नया फैसला: हम PostgreSQL डेटाबेस इस्तेमाल करेंगे", "hi"),
                new GroundTruthDoc(7L, 107L, "कल की मीटिंग सुबह 10 बजे तय हुई है", "hi"),
                new GroundTruthDoc(8L, 108L, "మన నిర్ణయం: మనం PostgreSQL డేటాబేస్ వాడుతున్నాము", "te"),
                new GroundTruthDoc(9L, 109L, "రేపు ఉదయం 10 గంటలకు టీమ్ మీటింగ్ ఉంది", "te"),
                new GroundTruthDoc(10L, 110L, "Alice assigned @yo_siri to review PR #42 for release 2026-10-24", "en"),
                new GroundTruthDoc(11L, 111L, "Correction: Release deadline moved from Friday to Monday Nov 4", "en"),
                new GroundTruthDoc(12L, 112L, "kal ka kaam kya hai bhai? PR review karna hai", "hinglish"),
                new GroundTruthDoc(13L, 113L, "repu mana pani enti ante database migration cheyali", "telugish")
        );

        // 2. Evaluation queries with expected ground truth IDs
        evalSet = List.of(
                // English direct
                new BenchmarkCase("what database did we decide to use?", "en", List.of(104L), false, false),
                // English multi-item
                new BenchmarkCase("what are all the tasks discussed?", "en", List.of(101L, 102L, 103L), true, false),
                // Hindi query for Hindi doc
                new BenchmarkCase("हमारा डेटाबेस का फैसला क्या था?", "hi", List.of(106L), false, false),
                // Hindi query for English doc (Cross-lingual)
                new BenchmarkCase("मीटिंग कब है?", "hi", List.of(105L, 107L), false, true),
                // Telugu query for Telugu doc
                new BenchmarkCase("డేటాబేస్ నిర్ణయం ఏమిటి?", "te", List.of(108L), false, false),
                // Telugu query for English doc (Cross-lingual)
                new BenchmarkCase("టీమ్ మీటింగ్ ఎప్పుడు ఉంది?", "te", List.of(105L, 109L), false, true),
                // Hinglish query for task
                new BenchmarkCase("kal ka kya kaam hai?", "hinglish", List.of(112L, 101L, 102L, 103L), true, true),
                // Telugish query
                new BenchmarkCase("repu mana pani enti?", "telugish", List.of(113L, 101L), false, true),
                // Unusual identifier
                new BenchmarkCase("what did Alice say about @yo_siri and 2026-10-24?", "en", List.of(110L), false, false)
        );
    }

    /**
     * Simulates hybrid retrieval against synthetic corpus using processor exact/cross-lingual terms and token overlap.
     */
    private List<SearchHit> simulateHybridSearch(String query, int topK) {
        var processed = queryProcessor.process(query);
        Set<String> queryTerms = new HashSet<>();

        // Add exact keywords
        for (String k : processed.exactKeywords()) {
            queryTerms.add(k.toLowerCase());
        }
        // Add cross-lingual terms
        for (String c : processed.crossLingualTerms()) {
            queryTerms.add(c.toLowerCase());
        }
        // Add cleaned words
        for (String w : processed.semanticQuery().toLowerCase().split("[\\s\\p{Punct}]+")) {
            if (w.length() >= 3) {
                queryTerms.add(w);
            }
        }

        List<SearchHit> candidates = new ArrayList<>();
        for (GroundTruthDoc doc : corpus) {
            double score = 0.0;
            String contentLower = doc.content().toLowerCase();

            for (String term : queryTerms) {
                if (contentLower.contains(term)) {
                    score += 1.0;
                }
            }

            // Headroom boost for exact identifier matches
            for (String exact : processed.exactKeywords()) {
                if (contentLower.contains(exact.toLowerCase())) {
                    score += 5.0;
                }
            }

            if (score > 0.0) {
                candidates.add(new SearchHit(
                        doc.messageId(),
                        doc.telegramMessageId(),
                        100L,
                        1L,
                        "user",
                        "User",
                        doc.content(),
                        Instant.now(),
                        Math.max(0.01, 1.0 - (score * 0.1))
                ));
            }
        }

        candidates.sort(Comparator.comparingDouble(SearchHit::distance));
        if (candidates.size() > topK) {
            return candidates.subList(0, topK);
        }
        return candidates;
    }

    @Test
    @DisplayName("Benchmark: Measures Recall@k, Precision@k, MRR, and Cross-Lingual Recall")
    void runRetrievalBenchmark() {
        int k = 5;
        double totalRecallAtK = 0.0;
        double totalPrecisionAtK = 0.0;
        double totalReciprocalRank = 0.0;

        int multiItemTotal = 0;
        int multiItemSuccess = 0;

        int crossLingualTotal = 0;
        int crossLingualSuccess = 0;

        for (BenchmarkCase testCase : evalSet) {
            List<SearchHit> retrieved = simulateHybridSearch(testCase.query(), k);
            Set<Long> retrievedIds = new HashSet<>();
            for (SearchHit hit : retrieved) {
                if (hit.telegramMessageId() != null) {
                    retrievedIds.add(hit.telegramMessageId());
                }
            }

            Set<Long> groundTruth = new HashSet<>(testCase.groundTruthMessageIds());

            // Compute intersections
            Set<Long> intersection = new HashSet<>(retrievedIds);
            intersection.retainAll(groundTruth);

            // Recall@k
            double recall = (double) intersection.size() / groundTruth.size();
            totalRecallAtK += recall;

            // Precision@k
            double precision = retrieved.isEmpty() ? 0.0 : (double) intersection.size() / retrieved.size();
            totalPrecisionAtK += precision;

            // MRR
            double reciprocalRank = 0.0;
            for (int rank = 0; rank < retrieved.size(); rank++) {
                if (groundTruth.contains(retrieved.get(rank).telegramMessageId())) {
                    reciprocalRank = 1.0 / (rank + 1);
                    break;
                }
            }
            totalReciprocalRank += reciprocalRank;

            // Multi-item evaluation
            if (testCase.isMultiItem()) {
                multiItemTotal++;
                if (intersection.containsAll(groundTruth) || (double) intersection.size() / groundTruth.size() >= 0.75) {
                    multiItemSuccess++;
                }
            }

            // Cross-lingual evaluation
            if (testCase.isCrossLingual()) {
                crossLingualTotal++;
                if (intersection.size() > 0) {
                    crossLingualSuccess++;
                }
            }
        }

        int n = evalSet.size();
        double meanRecallAtK = totalRecallAtK / n;
        double meanPrecisionAtK = totalPrecisionAtK / n;
        double mrr = totalReciprocalRank / n;
        double multiItemAccuracy = (double) multiItemSuccess / multiItemTotal;
        double crossLingualSuccessRate = (double) crossLingualSuccess / crossLingualTotal;

        System.out.printf("--- RETRIEVAL BENCHMARK RESULTS (N=%d, K=%d) ---%n", n, k);
        System.out.printf("Mean Recall@%d:            %.2f%%%n", k, meanRecallAtK * 100);
        System.out.printf("Mean Precision@%d:         %.2f%%%n", k, meanPrecisionAtK * 100);
        System.out.printf("Mean Reciprocal Rank (MRR): %.4f%n", mrr);
        System.out.printf("Multi-Item Accuracy:       %.2f%%%n", multiItemAccuracy * 100);
        System.out.printf("Cross-Lingual Success:     %.2f%%%n", crossLingualSuccessRate * 100);

        // Benchmark assertions verifying performance targets
        assertThat(meanRecallAtK).as("Mean Recall@5 must be >= 80%").isGreaterThanOrEqualTo(0.80);
        assertThat(mrr).as("MRR must be >= 0.85").isGreaterThanOrEqualTo(0.85);
        assertThat(crossLingualSuccessRate).as("Cross-lingual retrieval success rate must be >= 85%").isGreaterThanOrEqualTo(0.85);
        assertThat(multiItemAccuracy).as("Multi-item recall must achieve >= 80%").isGreaterThanOrEqualTo(0.80);
    }

    @Test
    @DisplayName("Benchmark: Citation validity and hallucination sanitization accuracy")
    void runCitationBenchmark() {
        SearchHit real1 = new SearchHit(1L, 101L, 100L, 1L, "alice", "Alice", "Setup Postgres", Instant.now(), 0.1);
        SearchHit real2 = new SearchHit(2L, 102L, 100L, 1L, "bob", "Bob", "Telegram webhook", Instant.now(), 0.2);
        List<SearchHit> hits = List.of(real1, real2);

        // 10 test responses with mixture of valid and hallucinated citations
        List<String> rawResponses = List.of(
                "Postgres setup is task 1 [Msg #101].",
                "Webhook setup is task 2 [Msg #102].",
                "Invalid citation from hallucination [Msg #999].",
                "Task 1 [Msg #101] and Task 99 [Msg #999] were discussed.",
                "Both tasks [Msg #101] and [Msg #102] are verified.",
                "Completely fictitious citation [Msg #8888] mentioned.",
                "No citations in this answer.",
                "Duplicate citations [Msg #101] and [Msg #101].",
                "Citations [Msg #102] and nonexistent [Msg #555].",
                "Task 1 is [Msg #101]."
        );

        int totalCitationsChecked = 0;
        int validCitationsRetained = 0;
        int hallucinatedCitationsStripped = 0;

        for (String raw : rawResponses) {
            String sanitized = citationValidator.validateAndSanitize(raw, hits);

            if (raw.contains("[Msg #101]")) {
                totalCitationsChecked++;
                if (sanitized.contains("[Msg #101]")) validCitationsRetained++;
            }
            if (raw.contains("[Msg #102]")) {
                totalCitationsChecked++;
                if (sanitized.contains("[Msg #102]")) validCitationsRetained++;
            }
            if (raw.contains("[Msg #999]")) {
                totalCitationsChecked++;
                if (!sanitized.contains("[Msg #999]")) hallucinatedCitationsStripped++;
            }
            if (raw.contains("[Msg #8888]")) {
                totalCitationsChecked++;
                if (!sanitized.contains("[Msg #8888]")) hallucinatedCitationsStripped++;
            }
            if (raw.contains("[Msg #555]")) {
                totalCitationsChecked++;
                if (!sanitized.contains("[Msg #555]")) hallucinatedCitationsStripped++;
            }
        }

        double citationAccuracy = (double) (validCitationsRetained + hallucinatedCitationsStripped) / totalCitationsChecked;
        System.out.printf("--- CITATION BENCHMARK RESULTS ---%n");
        System.out.printf("Total Citations Evaluated: %d%n", totalCitationsChecked);
        System.out.printf("Valid Citations Retained:  %d%n", validCitationsRetained);
        System.out.printf("Hallucinations Stripped:   %d%n", hallucinatedCitationsStripped);
        System.out.printf("Citation Accuracy:         %.2f%%%n", citationAccuracy * 100);

        assertThat(citationAccuracy).isEqualTo(1.0);
    }
}
