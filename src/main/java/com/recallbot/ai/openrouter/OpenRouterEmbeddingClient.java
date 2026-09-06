package com.recallbot.ai.openrouter;

import com.recallbot.ai.EmbeddingService;
import com.recallbot.ai.openrouter.dto.EmbeddingRequest;
import com.recallbot.ai.openrouter.dto.EmbeddingResponse;
import com.recallbot.config.properties.RecallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * OpenRouter implementation of {@link EmbeddingService} using Spring 6 {@link RestClient}.
 * Vectorizes text using configured embedding model (e.g. openai/text-embedding-3-small, 1536 dimensions).
 * Supports bounded retries with exponential backoff on HTTP 429 and 5xx errors.
 */
@Component
public class OpenRouterEmbeddingClient implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterEmbeddingClient.class);
    public static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    public static final int MAX_ATTEMPTS = 3;

    private final RestClient restClient;
    private final RecallProperties properties;
    private long initialBackoffMs = 500;

    @org.springframework.beans.factory.annotation.Autowired
    public OpenRouterEmbeddingClient(RecallProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, restClientBuilder, DEFAULT_BASE_URL);
    }

    public OpenRouterEmbeddingClient(RecallProperties properties, RestClient.Builder restClientBuilder, String baseUrl) {
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
    public float[] generateEmbedding(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Input text must not be null or blank");
        }

        List<float[]> embeddings = executeWithRetry(EmbeddingRequest.of(getModelName(), text));
        if (embeddings.isEmpty()) {
            throw new IllegalStateException("OpenRouter returned empty embedding data for input text");
        }
        return embeddings.get(0);
    }

    @Override
    public List<float[]> generateEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        return executeWithRetry(EmbeddingRequest.of(getModelName(), texts));
    }

    @Override
    public int getDimension() {
        return properties.ai() != null ? properties.ai().embeddingDimension() : 1536;
    }

    @Override
    public String getModelName() {
        return properties.ai() != null && properties.ai().embeddingModel() != null
                ? properties.ai().embeddingModel()
                : "openai/text-embedding-3-small";
    }

    private List<float[]> executeWithRetry(EmbeddingRequest request) {
        String apiKey = properties.ai() != null ? properties.ai().openrouterApiKey() : null;
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenRouter API key is not configured");
        }

        long backoff = initialBackoffMs;
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                log.debug("Calling OpenRouter embeddings endpoint for model={} (attempt={}/{})",
                        request.model(), attempt, MAX_ATTEMPTS);

                EmbeddingResponse response = restClient.post()
                        .uri("/embeddings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .body(request)
                        .retrieve()
                        .body(EmbeddingResponse.class);

                return validateAndExtractEmbeddings(response);
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
                log.warn("Network timeout/access error while connecting to OpenRouter (attempt={}/{}). Backing off for {}ms",
                        attempt, MAX_ATTEMPTS, backoff);
                if (attempt < MAX_ATTEMPTS) {
                    sleep(backoff);
                    backoff *= 2;
                }
            } catch (HttpClientErrorException e) {
                // 400 Bad Request, 401 Unauthorized, 403 Forbidden - do not retry
                log.error("OpenRouter client error HTTP {}: {}", e.getStatusCode(), e.getStatusText());
                throw e;
            } catch (Exception e) {
                lastException = e;
                log.error("Unexpected error calling OpenRouter embeddings: {}", e.getMessage());
                throw new IllegalStateException("Failed to generate embedding from OpenRouter: " + e.getMessage(), e);
            }
        }

        throw new IllegalStateException("OpenRouter embedding request failed after " + MAX_ATTEMPTS + " attempts", lastException);
    }

    private List<float[]> validateAndExtractEmbeddings(EmbeddingResponse response) {
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new IllegalStateException("OpenRouter returned null or empty response");
        }

        // Sort items by index to guarantee ordering matches input
        List<EmbeddingResponse.EmbeddingItem> items = new ArrayList<>(response.data());
        items.sort(Comparator.comparingInt(EmbeddingResponse.EmbeddingItem::index));

        int expectedDimension = getDimension();
        List<float[]> results = new ArrayList<>(items.size());

        for (EmbeddingResponse.EmbeddingItem item : items) {
            float[] vector = item.embedding();
            if (vector == null) {
                throw new IllegalStateException("OpenRouter returned null vector in embedding item index=" + item.index());
            }
            if (vector.length != expectedDimension) {
                throw new IllegalStateException(String.format(
                        "Vector dimension mismatch: expected %d, but model returned %d",
                        expectedDimension, vector.length
                ));
            }
            results.add(vector);
        }

        return results;
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
