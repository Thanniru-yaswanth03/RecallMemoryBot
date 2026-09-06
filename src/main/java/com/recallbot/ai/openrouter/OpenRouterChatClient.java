package com.recallbot.ai.openrouter;

import com.recallbot.ai.AIService;
import com.recallbot.ai.openrouter.dto.ChatCompletionRequest;
import com.recallbot.ai.openrouter.dto.ChatCompletionResponse;
import com.recallbot.config.properties.RecallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * OpenRouter implementation of {@link AIService} using Spring 6 {@link RestClient}.
 * Executes grounded LLM completions with bounded retries on transient errors.
 */
@Component
public class OpenRouterChatClient implements AIService {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterChatClient.class);
    public static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    public static final int MAX_ATTEMPTS = 3;

    private final RestClient restClient;
    private final RecallProperties properties;
    private long initialBackoffMs = 500;

    @Autowired
    public OpenRouterChatClient(RecallProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, restClientBuilder, DEFAULT_BASE_URL);
    }

    public OpenRouterChatClient(RecallProperties properties, RestClient.Builder restClientBuilder, String baseUrl) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("HTTP-Referer", "https://github.com/RecallMemoryBot")
                .defaultHeader("X-Title", "RecallMemoryBot")
                .build();
    }

    public void setInitialBackoffMs(long initialBackoffMs) {
        this.initialBackoffMs = initialBackoffMs;
    }

    @Override
    public String generateGroundedAnswer(String systemPrompt, String userPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("System prompt must not be null or blank");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("User prompt must not be null or blank");
        }

        String apiKey = properties.ai() != null ? properties.ai().openrouterApiKey() : null;
        if (apiKey == null || apiKey.isBlank() || "placeholder_api_key".equals(apiKey)) {
            throw new IllegalStateException("OpenRouter API key is not configured");
        }

        int maxTokens = properties.ai() != null && properties.ai().maxOutputTokens() > 0
                ? properties.ai().maxOutputTokens()
                : 800;

        double temperature = properties.ai() != null
                ? properties.ai().temperature()
                : 0.2;

        ChatCompletionRequest request = ChatCompletionRequest.of(
                getModelName(),
                systemPrompt,
                userPrompt,
                maxTokens,
                temperature
        );

        return executeWithRetry(request, apiKey);
    }

    @Override
    public String getModelName() {
        return properties.ai() != null && properties.ai().chatModel() != null
                ? properties.ai().chatModel()
                : "anthropic/claude-3-haiku";
    }

    private String executeWithRetry(ChatCompletionRequest request, String apiKey) {
        long backoff = initialBackoffMs;
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                log.debug("Calling OpenRouter chat completions endpoint for model={} (attempt={}/{})",
                        request.model(), attempt, MAX_ATTEMPTS);

                ChatCompletionResponse response = restClient.post()
                        .uri("/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .body(request)
                        .retrieve()
                        .body(ChatCompletionResponse.class);

                if (response == null || response.choices() == null || response.choices().isEmpty()) {
                    throw new IllegalStateException("OpenRouter returned null or empty choices");
                }

                String content = response.firstContent();
                if (content == null || content.isBlank()) {
                    throw new IllegalStateException("OpenRouter returned empty message content");
                }

                return content.trim();
            } catch (HttpClientErrorException.TooManyRequests e) {
                lastException = e;
                log.warn("OpenRouter HTTP 429 Rate Limit encountered (attempt={}/{}). Backing off for {}ms",
                        attempt, MAX_ATTEMPTS, backoff);
                if (attempt < MAX_ATTEMPTS) {
                    sleep(backoff);
                    backoff *= 2;
                }
            } catch (HttpServerErrorException e) {
                lastException = e;
                log.warn("OpenRouter server error HTTP {} (attempt={}/{}). Backing off for {}ms",
                        e.getStatusCode(), attempt, MAX_ATTEMPTS, backoff);
                if (attempt < MAX_ATTEMPTS) {
                    sleep(backoff);
                    backoff *= 2;
                }
            } catch (ResourceAccessException e) {
                lastException = e;
                log.warn("Network timeout/access error while connecting to OpenRouter chat (attempt={}/{}). Backing off for {}ms",
                        attempt, MAX_ATTEMPTS, backoff);
                if (attempt < MAX_ATTEMPTS) {
                    sleep(backoff);
                    backoff *= 2;
                }
            } catch (HttpClientErrorException e) {
                log.error("OpenRouter client error HTTP {}: {}", e.getStatusCode(), e.getStatusText());
                throw e;
            } catch (Exception e) {
                lastException = e;
                log.error("Unexpected error calling OpenRouter chat: {}", e.getMessage());
                throw new IllegalStateException("Failed to generate answer from OpenRouter: " + e.getMessage(), e);
            }
        }

        throw new IllegalStateException("OpenRouter chat completion failed after " + MAX_ATTEMPTS + " attempts", lastException);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during retry backoff", e);
        }
    }
}
