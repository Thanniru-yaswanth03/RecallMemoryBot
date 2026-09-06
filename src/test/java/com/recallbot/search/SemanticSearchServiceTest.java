package com.recallbot.search;

import com.recallbot.ai.EmbeddingService;
import com.recallbot.ai.EmbeddingTextNormalizer;
import com.recallbot.config.properties.RecallProperties;
import com.recallbot.search.dto.SearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SemanticSearchServiceTest {

    private static final int DIMENSION = 1536;

    @Mock
    private JdbcClient jdbcClient;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private EmbeddingTextNormalizer textNormalizer;

    private RecallProperties properties;
    private SemanticSearchServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new RecallProperties(
                new RecallProperties.Telegram("polling", "recall_bot", "token", "secret", null),
                new RecallProperties.Ai("key", "chat", "openai/text-embedding-3-small", DIMENSION, 30, 800, 0.2),
                new RecallProperties.Search(10, 60, 2500),
                new RecallProperties.RateLimit(3, 10)
        );

        lenient().when(embeddingService.getDimension()).thenReturn(DIMENSION);

        service = new SemanticSearchServiceImpl(jdbcClient, embeddingService, textNormalizer, properties);
    }

    @Test
    @DisplayName("Throws IllegalArgumentException if groupId is null")
    void rejectsNullGroupId() {
        assertThatThrownBy(() -> service.search(null, "query", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupId must not be null");

        assertThatThrownBy(() -> service.searchByVector(null, new float[DIMENSION], 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupId must not be null");
    }

    @Test
    @DisplayName("Returns empty list immediately when queryText is null, empty, or blank")
    void emptyOrBlankQueryReturnsEmptyList() {
        assertThat(service.search(1L, null, 5)).isEmpty();
        assertThat(service.search(1L, "", 5)).isEmpty();
        assertThat(service.search(1L, "    ", 5)).isEmpty();

        verifyNoInteractions(embeddingService);
    }

    @Test
    @DisplayName("Returns empty list immediately when topK is zero or negative")
    void invalidTopKReturnsEmptyList() {
        assertThat(service.search(1L, "valid query", 0)).isEmpty();
        assertThat(service.search(1L, "valid query", -5)).isEmpty();

        verifyNoInteractions(embeddingService);
    }

    @Test
    @DisplayName("Normalizes query text before generating embedding")
    void normalizesQueryTextBeforeGeneratingEmbedding() {
        String rawQuery = "  What   did we decide?  ";
        String normalizedQuery = "What did we decide?";
        float[] mockVector = new float[DIMENSION];

        when(textNormalizer.normalize(rawQuery)).thenReturn(normalizedQuery);
        when(embeddingService.generateEmbedding(normalizedQuery)).thenReturn(mockVector);

        // Mock JdbcClient fluent API chain
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(any())).thenReturn(statementSpec);
        JdbcClient.MappedQuerySpec<SearchHit> mappedQuerySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(statementSpec.query(any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(mappedQuerySpec);
        when(mappedQuerySpec.list()).thenReturn(Collections.emptyList());

        List<SearchHit> results = service.search(1L, rawQuery, 5);

        assertThat(results).isNotNull();
        verify(textNormalizer).normalize(rawQuery);
        verify(embeddingService).generateEmbedding(normalizedQuery);
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when query vector dimension does not match configured dimension")
    void dimensionMismatchThrowsException() {
        float[] wrongDimensionVector = new float[512];

        assertThatThrownBy(() -> service.searchByVector(1L, wrongDimensionVector, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Query vector dimension mismatch: expected 1536, but got 512");
    }

    @Test
    @DisplayName("Overloaded search without explicit topK uses configured search.maxCandidates")
    void defaultTopKUsesConfiguration() {
        String query = "architecture decision";
        float[] mockVector = new float[DIMENSION];

        when(textNormalizer.normalize(query)).thenReturn(query);
        when(embeddingService.generateEmbedding(query)).thenReturn(mockVector);

        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(any())).thenReturn(statementSpec);
        JdbcClient.MappedQuerySpec<SearchHit> mappedQuerySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(statementSpec.query(any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(mappedQuerySpec);
        when(mappedQuerySpec.list()).thenReturn(Collections.emptyList());

        service.search(100L, query);

        // Verify topK=10 (configured in setUp properties) was passed to the SQL statement
        verify(statementSpec).param(10);
    }
}
