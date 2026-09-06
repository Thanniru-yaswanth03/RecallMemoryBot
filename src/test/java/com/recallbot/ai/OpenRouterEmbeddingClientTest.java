package com.recallbot.ai;

import com.recallbot.ai.openrouter.OpenRouterEmbeddingClient;
import com.recallbot.config.properties.RecallProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenRouterEmbeddingClientTest {

    private static final String API_KEY = "test-sk-openrouter-key";
    private static final String MODEL = "openai/text-embedding-3-small";
    private static final int DIMENSION = 1536;

    private OpenRouterEmbeddingClient client;
    private MockRestServiceServer mockServer;

    private String buildVectorJson(int dimension, float value) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dimension; i++) {
            sb.append(value);
            if (i < dimension - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    @BeforeEach
    void setUp() {
        RecallProperties properties = new RecallProperties(
                new RecallProperties.Telegram("webhook", "recall_bot", "token", "secret", null),
                new RecallProperties.Ai(API_KEY, "chat", MODEL, DIMENSION, 30, 800, 0.2),
                new RecallProperties.Search(15, 60, 2500),
                new RecallProperties.RateLimit(3, 10)
        );

        RestClient.Builder restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        client = new OpenRouterEmbeddingClient(properties, restClientBuilder, "https://openrouter.ai/api/v1");
        client.setInitialBackoffMs(10); // fast test execution
    }

    @Test
    @DisplayName("Successfully generates 1024-dimensional embedding for single text")
    void generateEmbeddingSingle() {
        String mockResponse = """
                {
                  "object": "list",
                  "data": [
                    {
                      "object": "embedding",
                      "index": 0,
                      "embedding": %s
                    }
                  ],
                  "model": "%s",
                  "usage": { "prompt_tokens": 5, "total_tokens": 5 }
                }
                """.formatted(buildVectorJson(DIMENSION, 0.123f), MODEL);

        mockServer.expect(requestTo("https://openrouter.ai/api/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header("HTTP-Referer", "https://github.com/RecallMemoryBot"))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.input").value("Hello world"))
                .andRespond(withSuccess(mockResponse, MediaType.APPLICATION_JSON));

        float[] vector = client.generateEmbedding("Hello world");

        assertThat(vector).isNotNull();
        assertThat(vector).hasSize(DIMENSION);
        assertThat(vector[0]).isEqualTo(0.123f);
        mockServer.verify();
    }

    @Test
    @DisplayName("Successfully generates batch embeddings preserving original order")
    void generateEmbeddingsBatch() {
        String mockResponse = """
                {
                  "object": "list",
                  "data": [
                    { "object": "embedding", "index": 1, "embedding": %s },
                    { "object": "embedding", "index": 0, "embedding": %s }
                  ],
                  "model": "%s",
                  "usage": { "prompt_tokens": 10, "total_tokens": 10 }
                }
                """.formatted(buildVectorJson(DIMENSION, 0.2f), buildVectorJson(DIMENSION, 0.1f), MODEL);

        mockServer.expect(requestTo("https://openrouter.ai/api/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.input[0]").value("first"))
                .andExpect(jsonPath("$.input[1]").value("second"))
                .andRespond(withSuccess(mockResponse, MediaType.APPLICATION_JSON));

        List<float[]> results = client.generateEmbeddings(List.of("first", "second"));

        assertThat(results).hasSize(2);
        assertThat(results.get(0)[0]).isEqualTo(0.1f); // index 0
        assertThat(results.get(1)[0]).isEqualTo(0.2f); // index 1
        mockServer.verify();
    }

    @Test
    @DisplayName("Throws exception if model returns mismatched vector dimension")
    void dimensionMismatchThrowsException() {
        // Model returns 768 dimensions when 1024 is configured
        String mockResponse = """
                {
                  "object": "list",
                  "data": [
                    { "object": "embedding", "index": 0, "embedding": %s }
                  ],
                  "model": "%s",
                  "usage": { "prompt_tokens": 5, "total_tokens": 5 }
                }
                """.formatted(buildVectorJson(768, 0.1f), MODEL);

        mockServer.expect(requestTo("https://openrouter.ai/api/v1/embeddings"))
                .andRespond(withSuccess(mockResponse, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generateEmbedding("Test dimension mismatch"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Vector dimension mismatch: expected 1536, but model returned 768");

        mockServer.verify();
    }

    @Test
    @DisplayName("Rejects null or blank input text")
    void rejectsNullOrBlank() {
        assertThatThrownBy(() -> client.generateEmbedding(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.generateEmbedding("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
