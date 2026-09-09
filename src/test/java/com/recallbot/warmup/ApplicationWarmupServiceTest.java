package com.recallbot.warmup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recallbot.config.properties.RecallProperties;
import com.recallbot.telegram.TelegramClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApplicationWarmupServiceTest {

    @Mock
    private JdbcClient jdbcClient;

    @Mock
    private TelegramClient telegramClient;

    private RestClient.Builder restClientBuilder;
    private ObjectMapper objectMapper;
    private RecallProperties properties;
    private ApplicationWarmupService warmupService;

    @BeforeEach
    void setUp() {
        properties = new RecallProperties(
                new RecallProperties.Telegram("webhook", "recall_bot", "test_bot_token", "secret", null),
                new RecallProperties.Ai("test_api_key", "anthropic/claude-3-haiku", "openai/text-embedding-3-small", 1536, 30, 400, 0.2),
                new RecallProperties.Search(10, 60, 2500),
                new RecallProperties.RateLimit(3, 10)
        );

        restClientBuilder = RestClient.builder();
        objectMapper = new ObjectMapper();

        warmupService = new ApplicationWarmupService(
                jdbcClient,
                restClientBuilder,
                properties,
                telegramClient,
                objectMapper
        );
    }

    @Test
    @DisplayName("Warmup executes cleanly without throwing when database is reachable")
    void warmupExecutesCleanly() {
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        JdbcClient.MappedQuerySpec mappedQuerySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(statementSpec.query(any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(mappedQuerySpec);
        when(mappedQuerySpec.single()).thenReturn(0.0);

        assertThatCode(() -> warmupService.onApplicationReady()).doesNotThrowAnyException();
        verify(jdbcClient).sql(contains("vector"));
    }

    @Test
    @DisplayName("Warmup tolerates database or external API failures without throwing or failing startup")
    void warmupToleratesFailuresGracefully() {
        when(jdbcClient.sql(anyString())).thenThrow(new RuntimeException("Database connection refused"));

        assertThatCode(() -> warmupService.onApplicationReady()).doesNotThrowAnyException();
    }
}
