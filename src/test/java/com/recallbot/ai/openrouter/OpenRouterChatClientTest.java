package com.recallbot.ai.openrouter;

import com.recallbot.config.properties.RecallProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenRouterChatClientTest {

    private static final String API_KEY = "test-sk-chat-key";
    private static final String MODEL = "openrouter/free";

    private OpenRouterChatClient client;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RecallProperties properties = new RecallProperties(
                new RecallProperties.Telegram("webhook", "recall_bot", "token", "secret", null),
                new RecallProperties.Ai(API_KEY, MODEL, "openai/text-embedding-3-small", 1536, 30, 800, 0.2),
                new RecallProperties.Search(15, 60, 2500),
                new RecallProperties.RateLimit(3, 10)
        );

        RestClient.Builder restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        client = new OpenRouterChatClient(properties, restClientBuilder, "https://openrouter.ai/api/v1");
        client.setInitialBackoffMs(5); // fast test execution
    }

    @Test
    @DisplayName("Successfully generates grounded answer via OpenRouter chat completion")
    void generateGroundedAnswerSuccess() {
        String mockResponse = """
                {
                  "id": "gen-12345",
                  "model": "anthropic/claude-3-haiku",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "The team decided to use PostgreSQL on Sep 3 [Msg #101]."
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 50,
                    "completion_tokens": 20,
                    "total_tokens": 70
                  }
                }
                """;

        mockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header("HTTP-Referer", "https://github.com/RecallMemoryBot"))
                .andExpect(header("X-Title", "RecallMemoryBot"))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("System instructions"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("User question"))
                .andExpect(jsonPath("$.max_tokens").value(800))
                .andExpect(jsonPath("$.temperature").value(0.2))
                .andRespond(withSuccess(mockResponse, MediaType.APPLICATION_JSON));

        String answer = client.generateGroundedAnswer("System instructions", "User question");

        assertThat(answer).isEqualTo("The team decided to use PostgreSQL on Sep 3 [Msg #101].");
        mockServer.verify();
    }

    @Test
    @DisplayName("Retries on HTTP 429 Too Many Requests and succeeds on second attempt")
    void retriesOnHttp429() {
        String successResponse = """
                {
                  "id": "gen-12346",
                  "model": "anthropic/claude-3-haiku",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "Retried answer."
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """;

        mockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        mockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(successResponse, MediaType.APPLICATION_JSON));

        String answer = client.generateGroundedAnswer("System", "User");

        assertThat(answer).isEqualTo("Retried answer.");
        mockServer.verify();
    }

    @Test
    @DisplayName("Fails immediately on HTTP 401 Unauthorized without retrying")
    void failsImmediatelyOnHttp401() {
        mockServer.expect(ExpectedCount.once(), requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.generateGroundedAnswer("System", "User"))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);

        mockServer.verify();
    }

    @Test
    @DisplayName("Adaptively reduces max_tokens and succeeds when OpenRouter returns HTTP 402 Payment Required")
    void adaptivelyRetriesOnHttp402() {
        String error402 = """
                {
                  "error": {
                    "message": "This request requires more credits, or fewer max_tokens. You requested up to 800 tokens, but can only afford 705. To increase, visit https://openrouter.ai/settings/credits and upgrade to a paid account",
                    "code": 402
                  }
                }
                """;

        String successResponse = """
                {
                  "id": "gen-12347",
                  "model": "anthropic/claude-3-haiku",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "Adaptive answer after token reduction."
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """;

        // First attempt with 800 tokens fails with 402
        mockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.max_tokens").value(800))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).body(error402).contentType(MediaType.APPLICATION_JSON));

        // Second attempt with reduced tokens (705 budget - 10 prompt tokens = 695) succeeds
        mockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.max_tokens").value(695))
                .andRespond(withSuccess(successResponse, MediaType.APPLICATION_JSON));

        String answer = client.generateGroundedAnswer("System", "User");

        assertThat(answer).isEqualTo("Adaptive answer after token reduction.");
        mockServer.verify();
    }

    @Test
    @DisplayName("Fails with AIProviderCreditExhaustedException when OpenRouter credits are completely exhausted (below minimum floor)")
    void failsOnHttp402WhenCreditsCompletelyExhausted() {
        String error402Exhausted = """
                {
                  "error": {
                    "message": "This request requires more credits, or fewer max_tokens. You requested up to 800 tokens, but can only afford 50.",
                    "code": 402
                  }
                }
                """;

        mockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).body(error402Exhausted).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generateGroundedAnswer("System", "User"))
                .isInstanceOf(com.recallbot.ai.exception.AIProviderCreditExhaustedException.class)
                .hasMessageContaining("OpenRouter credits insufficient");

        mockServer.verify();
    }

    @Test
    @DisplayName("Fails with AIProviderCreditExhaustedException immediately when prompt tokens alone exceed affordable budget")
    void failsOnHttp402WhenPromptExceedsAffordableTokens() {
        String error402PromptExceeds = """
                {
                  "error": {
                    "message": "This request requires more credits, or fewer max_tokens. You requested up to 800 tokens, but can only afford 8.",
                    "code": 402
                  }
                }
                """;

        mockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).body(error402PromptExceeds).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generateGroundedAnswer("System", "User"))
                .isInstanceOf(com.recallbot.ai.exception.AIProviderCreditExhaustedException.class)
                .hasMessageContaining("prompt requires ~10 tokens, but account can only afford 8 total tokens");

        mockServer.verify();
    }

    @Test
    @DisplayName("Throws AIProviderRateLimitException when all retry attempts fail with HTTP 429")
    void throwsRateLimitExceptionWhenAllAttemptsFail() {
        for (int i = 0; i < OpenRouterChatClient.MAX_ATTEMPTS; i++) {
            mockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        }

        assertThatThrownBy(() -> client.generateGroundedAnswer("System", "User"))
                .isInstanceOf(com.recallbot.ai.exception.AIProviderRateLimitException.class)
                .hasMessageContaining("OpenRouter rate limit exceeded");

        mockServer.verify();
    }

    @Test
    @DisplayName("Throws AIProviderUnavailableException when server returns HTTP 500 across all attempts")
    void throwsUnavailableExceptionWhenServerFails() {
        for (int i = 0; i < OpenRouterChatClient.MAX_ATTEMPTS; i++) {
            mockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        }

        assertThatThrownBy(() -> client.generateGroundedAnswer("System", "User"))
                .isInstanceOf(com.recallbot.ai.exception.AIProviderUnavailableException.class)
                .hasMessageContaining("OpenRouter chat completion failed");

        mockServer.verify();
    }

    @Test
    @DisplayName("Throws exception on null or blank prompts")
    void rejectsNullOrBlankPrompts() {
        assertThatThrownBy(() -> client.generateGroundedAnswer(null, "User"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.generateGroundedAnswer("System", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.generateGroundedAnswer("   ", "User"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Falls back to DEFAULT_FREE_MODEL when configured model returns HTTP 404")
    void fallsBackToOpenRouterFreeOnHttp404() {
        RecallProperties customProps = new RecallProperties(
                new RecallProperties.Telegram("webhook", "recall_bot", "token", "secret", null),
                new RecallProperties.Ai(API_KEY, "custom/deprecated-model", "openai/text-embedding-3-small", 1536, 30, 800, 0.2),
                new RecallProperties.Search(15, 60, 2500),
                new RecallProperties.RateLimit(3, 10)
        );
        RestClient.Builder customBuilder = RestClient.builder();
        MockRestServiceServer customMockServer = MockRestServiceServer.bindTo(customBuilder).build();
        OpenRouterChatClient customClient = new OpenRouterChatClient(customProps, customBuilder, "https://openrouter.ai/api/v1");
        customClient.setInitialBackoffMs(5);

        String successResponse = """
                {
                  "id": "gen-fallback",
                  "model": "inclusionai/ling-3.0-flash-sante:free",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "Recovered via fallback."
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """;

        customMockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value("custom/deprecated-model"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        customMockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value(OpenRouterChatClient.DEFAULT_FREE_MODEL))
                .andRespond(withSuccess(successResponse, MediaType.APPLICATION_JSON));

        String answer = customClient.generateGroundedAnswer("System instructions", "User question");
        assertThat(answer).isEqualTo("Recovered via fallback.");
        customMockServer.verify();
    }

    @Test
    @DisplayName("Falls back to FALLBACK_FREE_MODEL when primary model returns null or blank content (e.g. reasoning token exhaustion)")
    void fallsBackOnEmptyContent() {
        RecallProperties customProps = new RecallProperties(
                new RecallProperties.Telegram("webhook", "recall_bot", "token", "secret", null),
                new RecallProperties.Ai(API_KEY, OpenRouterChatClient.DEFAULT_FREE_MODEL, "openai/text-embedding-3-small", 1536, 30, 800, 0.2),
                new RecallProperties.Search(15, 60, 2500),
                new RecallProperties.RateLimit(3, 10)
        );
        RestClient.Builder customBuilder = RestClient.builder();
        MockRestServiceServer customMockServer = MockRestServiceServer.bindTo(customBuilder).build();
        OpenRouterChatClient customClient = new OpenRouterChatClient(customProps, customBuilder, "https://openrouter.ai/api/v1");
        customClient.setInitialBackoffMs(5);

        String emptyResponse = """
                {
                  "id": "gen-empty",
                  "model": "inclusionai/ling-3.0-flash-sante:free",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": null
                      },
                      "finish_reason": "length"
                    }
                  ]
                }
                """;

        String successResponse = """
                {
                  "id": "gen-fallback-success",
                  "model": "openrouter/free",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "Grounded answer from fallback model."
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """;

        // First attempt with primary model returns null content
        customMockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value(OpenRouterChatClient.DEFAULT_FREE_MODEL))
                .andRespond(withSuccess(emptyResponse, MediaType.APPLICATION_JSON));

        // Second attempt with FALLBACK_FREE_MODEL succeeds
        customMockServer.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value(OpenRouterChatClient.FALLBACK_FREE_MODEL))
                .andRespond(withSuccess(successResponse, MediaType.APPLICATION_JSON));

        String answer = customClient.generateGroundedAnswer("System instructions", "User question");
        assertThat(answer).isEqualTo("Grounded answer from fallback model.");
        customMockServer.verify();
    }
}
