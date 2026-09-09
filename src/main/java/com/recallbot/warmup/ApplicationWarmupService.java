package com.recallbot.warmup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recallbot.ai.openrouter.dto.ChatCompletionRequest;
import com.recallbot.ai.openrouter.dto.ChatCompletionResponse;
import com.recallbot.ai.openrouter.dto.EmbeddingRequest;
import com.recallbot.config.properties.RecallProperties;
import com.recallbot.telegram.TelegramClient;
import com.recallbot.telegram.dto.UpdateDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Executes eager startup pre-warming and readiness initialization upon ApplicationReadyEvent.
 * Prevents 1-3 minute cold-start delays on the first user command by pre-initializing:
 * 1. PostgreSQL connection pool & pgvector library loading.
 * 2. OpenRouter DNS resolution, TCP handshake, TLS session, and API authentication.
 * 3. Telegram API DNS resolution, TCP handshake, and TLS connection pooling.
 * 4. Jackson JSON serialization reflection metadata.
 */
@Service
public class ApplicationWarmupService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationWarmupService.class);

    private final JdbcClient jdbcClient;
    private final RestClient.Builder restClientBuilder;
    private final RecallProperties properties;
    private final TelegramClient telegramClient;
    private final ObjectMapper objectMapper;

    public ApplicationWarmupService(
            JdbcClient jdbcClient,
            RestClient.Builder restClientBuilder,
            RecallProperties properties,
            TelegramClient telegramClient,
            ObjectMapper objectMapper
    ) {
        this.jdbcClient = jdbcClient;
        this.restClientBuilder = restClientBuilder;
        this.properties = properties;
        this.telegramClient = telegramClient;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        long overallStart = System.currentTimeMillis();
        log.info("Starting eager application warmup and readiness initialization...");

        warmupDatabaseAndPgVector();
        warmupOpenRouter();
        warmupTelegram();
        warmupJackson();

        long totalWarmupMs = System.currentTimeMillis() - overallStart;
        log.info("Application warmup completed successfully in {}ms. All pools and connections are pre-warmed.", totalWarmupMs);
    }

    private void warmupDatabaseAndPgVector() {
        long start = System.currentTimeMillis();
        try {
            // Wakes up cloud DB (e.g. Neon), pre-allocates Hikari pool connection, and pre-loads vector.so
            Double dist = jdbcClient.sql("SELECT '[0,0]'::vector <-> '[0,0]'::vector AS dist")
                    .query((rs, rowNum) -> rs.getDouble("dist"))
                    .single();
            log.info("Database & pgvector pre-warmed in {}ms (test query result: {})", System.currentTimeMillis() - start, dist);
        } catch (Exception e) {
            log.warn("Database & pgvector warmup failed or skipped: {}", e.getMessage());
        }
    }

    private void warmupOpenRouter() {
        String apiKey = properties.ai() != null ? properties.ai().openrouterApiKey() : null;
        if (apiKey == null || apiKey.isBlank() || "placeholder_api_key".equals(apiKey)) {
            log.debug("Skipping OpenRouter warmup: API key not configured");
            return;
        }

        long start = System.currentTimeMillis();
        try {
            RestClient client = restClientBuilder
                    .baseUrl("https://openrouter.ai/api/v1")
                    .defaultHeader("HTTP-Referer", "https://github.com/RecallMemoryBot")
                    .defaultHeader("X-Title", "RecallMemoryBot")
                    .build();

            // Lightweight auth check: resolves DNS, executes TLS handshake, and establishes pooled HTTP/2 connection
            client.get()
                    .uri("/auth/key")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .retrieve()
                    .toBodilessEntity();

            log.info("OpenRouter HTTP/2 connection and TLS session pre-warmed in {}ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("OpenRouter warmup probe encountered non-fatal response: {}", e.getMessage());
        }
    }

    private void warmupTelegram() {
        String botToken = properties.telegram() != null ? properties.telegram().botToken() : null;
        if (botToken == null || botToken.isBlank() || "placeholder_token".equals(botToken)) {
            log.debug("Skipping Telegram warmup: bot token not configured");
            return;
        }

        long start = System.currentTimeMillis();
        try {
            RestClient client = restClientBuilder
                    .baseUrl("https://api.telegram.org/bot" + botToken)
                    .build();

            // Lightweight getMe call to pre-warm DNS, TLS, and keep-alive pool for api.telegram.org
            client.get()
                    .uri("/getMe")
                    .retrieve()
                    .toBodilessEntity();

            log.info("Telegram API connection and TLS session pre-warmed in {}ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("Telegram API warmup probe encountered non-fatal response: {}", e.getMessage());
        }
    }

    private void warmupJackson() {
        long start = System.currentTimeMillis();
        try {
            ChatCompletionRequest sampleChatReq = ChatCompletionRequest.of("model", "system", "user", 100, 0.2);
            byte[] chatBytes = objectMapper.writeValueAsBytes(sampleChatReq);
            objectMapper.readValue(chatBytes, ChatCompletionRequest.class);

            EmbeddingRequest sampleEmbReq = EmbeddingRequest.of("model", "sample text");
            byte[] embBytes = objectMapper.writeValueAsBytes(sampleEmbReq);
            objectMapper.readValue(embBytes, EmbeddingRequest.class);

            String sampleChoice = "{\"id\":\"1\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";
            objectMapper.readValue(sampleChoice, ChatCompletionResponse.class);

            String sampleUpdate = "{\"update_id\":1}";
            objectMapper.readValue(sampleUpdate, UpdateDto.class);

            log.debug("Jackson serializers and reflection caches pre-warmed in {}ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("Jackson serialization warmup encountered non-fatal warning: {}", e.getMessage());
        }
    }
}
