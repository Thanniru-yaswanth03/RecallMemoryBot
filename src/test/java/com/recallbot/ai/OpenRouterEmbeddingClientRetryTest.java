package com.recallbot.ai;

import com.recallbot.ai.openrouter.OpenRouterEmbeddingClient;
import com.recallbot.config.properties.RecallProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenRouterEmbeddingClientRetryTest {

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
        client.setInitialBackoffMs(5); // fast test execution
    }

    @Test
    @DisplayName("Retries on HTTP 429 Too Many Requests and succeeds on second attempt")
    void retriesOnHttp429() {
        String successResponse = """
                {
                  "object": "list",
                  "data": [
                    { "object": "embedding", "index": 0, "embedding": %s }
                  ],
                  "model": "%s",
                  "usage": { "prompt_tokens": 5, "total_tokens": 5 }
                }
                """.formatted(buildVectorJson(DIMENSION, 0.5f), MODEL);

        mockServer.expect(requestTo("https://openrouter.ai/api/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        mockServer.expect(requestTo("https://openrouter.ai/api/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(successResponse, MediaType.APPLICATION_JSON));

        float[] vector = client.generateEmbedding("Test 429 retry");

        assertThat(vector).isNotNull();
        assertThat(vector[0]).isEqualTo(0.5f);
        mockServer.verify();
    }

    @Test
    @DisplayName("Retries on HTTP 503 Service Unavailable and succeeds on third attempt")
    void retriesOnHttp503() {
        String successResponse = """
                {
                  "object": "list",
                  "data": [
                    { "object": "embedding", "index": 0, "embedding": %s }
                  ],
                  "model": "%s",
                  "usage": { "prompt_tokens": 5, "total_tokens": 5 }
                }
                """.formatted(buildVectorJson(DIMENSION, 0.9f), MODEL);

        mockServer.expect(requestTo("https://openrouter.ai/api/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        mockServer.expect(requestTo("https://openrouter.ai/api/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        mockServer.expect(requestTo("https://openrouter.ai/api/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(successResponse, MediaType.APPLICATION_JSON));

        float[] vector = client.generateEmbedding("Test 503 retry");

        assertThat(vector).isNotNull();
        assertThat(vector[0]).isEqualTo(0.9f);
        mockServer.verify();
    }

    @Test
    @DisplayName("Exhausts retries after 3 attempts on persistent HTTP 500")
    void exhaustsRetriesOnHttp500() {
        mockServer.expect(ExpectedCount.times(3), requestTo("https://openrouter.ai/api/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.generateEmbedding("Test persistent 500"))
                .isInstanceOf(com.recallbot.ai.exception.AIProviderUnavailableException.class)
                .hasMessageContaining("failed after 3 attempts");

        mockServer.verify();
    }

    @Test
    @DisplayName("Fails immediately on HTTP 401 Unauthorized without retrying")
    void failsImmediatelyOnHttp401() {
        mockServer.expect(ExpectedCount.once(), requestTo("https://openrouter.ai/api/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.generateEmbedding("Test 401"))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);

        mockServer.verify();
    }
}
