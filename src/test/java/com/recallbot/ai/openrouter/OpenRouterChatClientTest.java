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
    private static final String MODEL = "anthropic/claude-3-haiku";

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
    @DisplayName("Throws exception on null or blank prompts")
    void rejectsNullOrBlankPrompts() {
        assertThatThrownBy(() -> client.generateGroundedAnswer(null, "User"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.generateGroundedAnswer("System", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.generateGroundedAnswer("   ", "User"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
