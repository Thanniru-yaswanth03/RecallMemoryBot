package com.recallbot.resilience;

import com.recallbot.ai.AIService;
import com.recallbot.ai.EmbeddingService;
import com.recallbot.ai.citation.CitationValidator;
import com.recallbot.ai.openrouter.OpenRouterChatClient;
import com.recallbot.ai.prompt.PromptBuilder;
import com.recallbot.config.properties.RecallProperties;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipService;
import com.recallbot.core.group.GroupRole;
import com.recallbot.core.group.GroupService;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.core.message.event.MessagePersistedEvent;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserService;
import com.recallbot.persistence.BasePostgresIntegrationTest;
import com.recallbot.search.SemanticSearchService;
import com.recallbot.search.dto.SearchHit;
import com.recallbot.security.RateLimiter;
import com.recallbot.telegram.TelegramClient;
import com.recallbot.telegram.command.AskCommandHandler;
import com.recallbot.telegram.dto.ChatDto;
import com.recallbot.telegram.dto.MessageDto;
import com.recallbot.telegram.dto.UserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
class OpenRouterFailureResilienceTest extends BasePostgresIntegrationTest {

    private static final String API_KEY = "test-sk-openrouter-key";
    private static final String CHAT_MODEL = "anthropic/claude-3-haiku";

    @Autowired
    private GroupService groupService;

    @Autowired
    private UserService userService;

    @Autowired
    private GroupMembershipService groupMembershipService;

    @Autowired
    private MessageRepository messageRepository;

    @MockBean
    private EmbeddingService embeddingService;

    private RecallProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RecallProperties(
                new RecallProperties.Telegram("webhook", "recall_bot", "token", "secret", null),
                new RecallProperties.Ai(API_KEY, CHAT_MODEL, "openai/text-embedding-3-small", 1536, 30, 800, 0.2),
                new RecallProperties.Search(10, 60, 2500),
                new RecallProperties.RateLimit(100, 500)
        );
    }

    @Test
    @DisplayName("OpenRouter HTTP 500 retries 3 times and throws IllegalStateException upon retry exhaustion")
    void testOpenRouter500ServerErrorRetriesAndExhausts() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        OpenRouterChatClient client = new OpenRouterChatClient(properties, restClientBuilder, "https://openrouter.ai/api/v1");
        client.setInitialBackoffMs(5); // fast backoff for tests

        // Expect 3 POST attempts, each returning 500 Internal Server Error
        mockServer.expect(ExpectedCount.times(3), requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.generateGroundedAnswer("System", "User question"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed after 3 attempts");

        mockServer.verify();
    }

    @Test
    @DisplayName("OpenRouter network timeout (ResourceAccessException) retries 3 times and exhausts cleanly")
    void testOpenRouterTimeoutRetriesAndExhausts() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        OpenRouterChatClient client = new OpenRouterChatClient(properties, restClientBuilder, "https://openrouter.ai/api/v1");
        client.setInitialBackoffMs(5);

        mockServer.expect(ExpectedCount.times(3), requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    throw new ResourceAccessException("I/O error: Connection timed out", new IOException("Read timed out"));
                });

        assertThatThrownBy(() -> client.generateGroundedAnswer("System", "User question"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed after 3 attempts");

        mockServer.verify();
    }

    @Test
    @DisplayName("OpenRouter transient 500 error recovers on second attempt with grounded answer")
    void testOpenRouterTransient500RecoversOnRetry() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        OpenRouterChatClient client = new OpenRouterChatClient(properties, restClientBuilder, "https://openrouter.ai/api/v1");
        client.setInitialBackoffMs(5);

        String successJson = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "Recovered answer from OpenRouter [Msg #101]."
                      }
                    }
                  ]
                }
                """;

        // Attempt 1: 500
        mockServer.expect(ExpectedCount.once(), requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        // Attempt 2: 200 OK
        mockServer.expect(ExpectedCount.once(), requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(successJson, MediaType.APPLICATION_JSON));

        String answer = client.generateGroundedAnswer("System", "User question");
        assertThat(answer).isEqualTo("Recovered answer from OpenRouter [Msg #101].");

        mockServer.verify();
    }

    @Test
    @DisplayName("AskCommandHandler degrades gracefully when OpenRouter throws exception, sending fallback without crashing")
    void testAskCommandHandlerGracefulDegradationOnAiFailure() {
        TelegramClient telegramClient = mock(TelegramClient.class);
        AIService failingAiService = mock(AIService.class);
        SemanticSearchService searchService = mock(SemanticSearchService.class);

        when(failingAiService.generateGroundedAnswer(anyString(), anyString()))
                .thenThrow(new IllegalStateException("OpenRouter chat completion failed after 3 attempts"));

        SearchHit hit = new SearchHit(1L, 100L, 10L, 20L, "john", "John", "Postgres is great", Instant.now(), 0.1);
        when(searchService.search(anyLong(), anyString(), anyInt())).thenReturn(List.of(hit));

        AskCommandHandler handler = new AskCommandHandler(
                groupService,
                userService,
                groupMembershipService,
                searchService,
                failingAiService,
                new PromptBuilder(),
                new CitationValidator(),
                telegramClient,
                properties,
                new RateLimiter(properties)
        );

        ChatDto chat = new ChatDto(-999123L, "supergroup", "Test Chat", null);
        UserDto user = new UserDto(111L, false, "alice", "Alice", null);
        MessageDto message = new MessageDto(555L, user, chat, System.currentTimeMillis() / 1000L, "/ask what database?", null, null);

        // Should not throw unhandled exception
        handler.handle(message);

        // Verify fallback message sent to Telegram
        verify(telegramClient).sendMessage(
                eq(-999123L),
                eq("I encountered an error retrieving memories or generating an answer. Please try again later."),
                isNull(),
                eq(555L)
        );
    }

    @Test
    @DisplayName("Database state remains intact and uncorrupted when AI generation fails during /ask")
    void testDatabaseStateRemainsIntactDuringAiFailure() {
        long chatId = -777000L - (System.currentTimeMillis() % 100000L);
        ChatDto chatDto = new ChatDto(chatId, "supergroup", "Resilience DB Group " + UUID.randomUUID(), null);
        GroupEntity group = groupService.resolveGroup(chatDto);

        UserDto userDto = new UserDto(888123L, false, "db_user", "DB User", null);
        UserEntity user = userService.resolveUser(userDto);
        groupMembershipService.ensureMembership(group, user, GroupRole.MEMBER);

        // Persist pre-existing message
        MessageEntity savedMsg = messageRepository.save(new MessageEntity(
                group, user, 40404L, "Crucial historical decision to keep intact", Instant.now()
        ));

        TelegramClient telegramClient = mock(TelegramClient.class);
        AIService failingAiService = mock(AIService.class);
        SemanticSearchService searchService = mock(SemanticSearchService.class);

        when(failingAiService.generateGroundedAnswer(anyString(), anyString()))
                .thenThrow(new IllegalStateException("Simulated OpenRouter outage"));

        SearchHit hit = new SearchHit(savedMsg.getId(), savedMsg.getTelegramMessageId(), group.getId(), user.getId(),
                user.getUsername(), user.getFirstName(), savedMsg.getContent(), savedMsg.getSentAt(), 0.05);
        when(searchService.search(eq(group.getId()), anyString(), anyInt())).thenReturn(List.of(hit));

        AskCommandHandler handler = new AskCommandHandler(
                groupService,
                userService,
                groupMembershipService,
                searchService,
                failingAiService,
                new PromptBuilder(),
                new CitationValidator(),
                telegramClient,
                properties,
                new RateLimiter(properties)
        );

        MessageDto queryMessage = new MessageDto(
                99901L, userDto, chatDto, System.currentTimeMillis() / 1000L, "/ask what was our crucial decision?", null, null
        );

        handler.handle(queryMessage);

        // Verify fallback message sent to Telegram
        verify(telegramClient).sendMessage(
                eq(chatId),
                eq("I encountered an error retrieving memories or generating an answer. Please try again later."),
                isNull(),
                eq(99901L)
        );

        // Verify message still exists intact in the database
        MessageEntity retrieved = messageRepository.findById(savedMsg.getId()).orElseThrow();
        assertThat(retrieved.getContent()).isEqualTo("Crucial historical decision to keep intact");
        assertThat(retrieved.getTelegramMessageId()).isEqualTo(40404L);
        assertThat(retrieved.getGroup().getId()).isEqualTo(group.getId());
    }
}
