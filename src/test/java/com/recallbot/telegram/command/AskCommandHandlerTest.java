package com.recallbot.telegram.command;

import com.recallbot.ai.AIService;
import com.recallbot.ai.citation.CitationValidator;
import com.recallbot.ai.prompt.PromptBuilder;
import com.recallbot.config.properties.RecallProperties;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipService;
import com.recallbot.core.group.GroupRole;
import com.recallbot.core.group.GroupService;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserService;
import com.recallbot.search.SemanticSearchService;
import com.recallbot.search.dto.SearchHit;
import com.recallbot.telegram.TelegramClient;
import com.recallbot.telegram.dto.ChatDto;
import com.recallbot.telegram.dto.MessageDto;
import com.recallbot.telegram.dto.UserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AskCommandHandlerTest {

    @Mock
    private GroupService groupService;
    @Mock
    private UserService userService;
    @Mock
    private GroupMembershipService groupMembershipService;
    @Mock
    private SemanticSearchService semanticSearchService;
    @Mock
    private AIService aiService;
    @Mock
    private TelegramClient telegramClient;

    private PromptBuilder promptBuilder;
    private CitationValidator citationValidator;
    private RecallProperties properties;
    private com.recallbot.security.RateLimiter rateLimiter;
    private AskCommandHandler handler;

    private final Long telegramChatId = -100999L;
    private final Long databaseGroupId = 42L;
    private final Long telegramUserId = 12345L;
    private final Long databaseUserId = 1L;
    private final Long telegramMessageId = 888L;

    private GroupEntity mockGroup;
    private UserEntity mockUser;

    private com.recallbot.admin.activity.AdminActivityBuffer adminActivityBuffer;

    @BeforeEach
    void setUp() {
        promptBuilder = new PromptBuilder();
        citationValidator = new CitationValidator();
        properties = new RecallProperties(
                new RecallProperties.Telegram("webhook", "recall_bot", "token", "secret", null),
                new RecallProperties.Ai("key", "claude", "openai/text-embedding-3-small", 1536, 30, 800, 0.2),
                new RecallProperties.Search(10, 60, 2500),
                new RecallProperties.RateLimit(3, 10)
        );
        rateLimiter = new com.recallbot.security.RateLimiter(properties);

        handler = new AskCommandHandler(
                groupService,
                userService,
                groupMembershipService,
                semanticSearchService,
                aiService,
                promptBuilder,
                citationValidator,
                telegramClient,
                properties,
                rateLimiter
        );
        adminActivityBuffer = new com.recallbot.admin.activity.AdminActivityBuffer();
        handler.setAdminActivityBuffer(adminActivityBuffer);

        mockGroup = new GroupEntity(telegramChatId, "Test Group");
        mockGroup.setId(databaseGroupId);

        mockUser = new UserEntity(telegramUserId, "alice", "Alice", null);
        mockUser.setId(databaseUserId);
    }

    @Test
    @DisplayName("canHandle responds to both /ask and /recall alias")
    void canHandleMatchesAskAndRecall() {
        assertThat(handler.canHandle("/ask")).isTrue();
        assertThat(handler.canHandle("/ASK")).isTrue();
        assertThat(handler.canHandle("/recall")).isTrue();
        assertThat(handler.canHandle("/RECALL")).isTrue();
        assertThat(handler.canHandle("/remember")).isFalse();
    }

    @Test
    @DisplayName("1 & 2 & 3 & 4 & 5 & 9: Full valid /ask flow extracts question, enforces group isolation, retrieves hits, builds context, generates grounded answer, and replies via Telegram")
    void validAskCommandExecutesEndToEnd() {
        MessageDto message = createMessage("/ask when did we decide to use PostgreSQL?");

        when(groupService.resolveGroup(any())).thenReturn(mockGroup);
        when(userService.resolveUser(any())).thenReturn(mockUser);

        SearchHit hit = new SearchHit(
                1L, 101L, databaseGroupId, databaseUserId, "bob", "Bob",
                "We chose PostgreSQL on Tuesday.", Instant.parse("2026-09-01T10:00:00Z"), 0.05
        );
        when(semanticSearchService.search(eq(databaseGroupId), eq("when did we decide to use PostgreSQL?"), eq(10)))
                .thenReturn(List.of(hit));

        when(aiService.generateGroundedAnswer(anyString(), anyString()))
                .thenReturn("The team chose PostgreSQL on Tuesday [Msg #101].");

        handler.handle(message);

        // 1. Question extraction & 2. Group isolation verified in semantic search call
        verify(semanticSearchService).search(eq(databaseGroupId), eq("when did we decide to use PostgreSQL?"), eq(10));

        // 3. Typing indicator sent
        verify(telegramClient).sendChatAction(eq(telegramChatId), eq("typing"));

        // 4 & 5. Verify prompt sent to AI contains user question and bounded context
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiService).generateGroundedAnswer(systemPromptCaptor.capture(), userPromptCaptor.capture());

        assertThat(systemPromptCaptor.getValue()).contains("CRITICAL GROUNDING RULES");
        assertThat(userPromptCaptor.getValue()).contains("<conversation_history>");
        assertThat(userPromptCaptor.getValue()).contains("[Msg #101] @bob");
        assertThat(userPromptCaptor.getValue()).contains("We chose PostgreSQL on Tuesday.");
        assertThat(userPromptCaptor.getValue()).contains("User Question: when did we decide to use PostgreSQL?");

        // 9. Answer sent back via TelegramClient threaded to original message
        verify(telegramClient).sendMessage(
                eq(telegramChatId),
                eq("The team chose PostgreSQL on Tuesday [Msg #101]."),
                isNull(),
                eq(telegramMessageId)
        );
    }

    @Test
    @DisplayName("6: Handles zero results gracefully without invoking AI")
    void handlesZeroResults() {
        MessageDto message = createMessage("/ask What is the secret password?");

        when(groupService.resolveGroup(any())).thenReturn(mockGroup);
        when(userService.resolveUser(any())).thenReturn(mockUser);
        when(semanticSearchService.search(eq(databaseGroupId), eq("What is the secret password?"), eq(10)))
                .thenReturn(Collections.emptyList());

        handler.handle(message);

        // Does NOT invoke AI
        verify(aiService, never()).generateGroundedAnswer(anyString(), anyString());

        // Sends deterministic fallback
        verify(telegramClient).sendMessage(
                eq(telegramChatId),
                eq("I don't have enough conversation history in this group to answer that question."),
                isNull(),
                eq(telegramMessageId)
        );
    }

    @Test
    @DisplayName("7: Handles empty /ask command without question by sending usage guidance")
    void handlesEmptyAskCommand() {
        MessageDto message = createMessage("/ask   ");

        handler.handle(message);

        // Skips group resolution, retrieval, and AI
        verify(semanticSearchService, never()).search(anyLong(), anyString(), anyInt());
        verify(aiService, never()).generateGroundedAnswer(anyString(), anyString());

        // Sends usage guidance
        verify(telegramClient).sendMessage(
                eq(telegramChatId),
                contains("Please provide a question after /ask"),
                isNull(),
                eq(telegramMessageId)
        );
    }

    @Test
    @DisplayName("8: Handles AI service failure safely without crashing")
    void handlesAiFailureSafely() {
        MessageDto message = createMessage("/ask When is the release?");

        when(groupService.resolveGroup(any())).thenReturn(mockGroup);
        when(userService.resolveUser(any())).thenReturn(mockUser);

        SearchHit hit = new SearchHit(
                2L, 202L, databaseGroupId, databaseUserId, "bob", "Bob",
                "Release is next Friday.", Instant.now(), 0.1
        );
        when(semanticSearchService.search(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of(hit));

        when(aiService.generateGroundedAnswer(anyString(), anyString()))
                .thenThrow(new IllegalStateException("OpenRouter timeout"));

        handler.handle(message);

        // Graceful error response sent to Telegram
        verify(telegramClient).sendMessage(
                eq(telegramChatId),
                eq("I encountered an error retrieving memories or generating an answer. Please try again later."),
                isNull(),
                eq(telegramMessageId)
        );
    }

    @Test
    @DisplayName("Handles AI provider credit exhaustion with clear guidance and admin activity recording")
    void handlesAiCreditExhaustionGracefully() {
        MessageDto message = createMessage("/ask When is the release?");

        when(groupService.resolveGroup(any())).thenReturn(mockGroup);
        when(userService.resolveUser(any())).thenReturn(mockUser);

        SearchHit hit = new SearchHit(
                2L, 202L, databaseGroupId, databaseUserId, "bob", "Bob",
                "Release is next Friday.", Instant.now(), 0.1
        );
        when(semanticSearchService.search(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of(hit));

        when(aiService.generateGroundedAnswer(anyString(), anyString()))
                .thenThrow(new com.recallbot.ai.exception.AIProviderCreditExhaustedException("OpenRouter credits insufficient"));

        handler.handle(message);

        verify(telegramClient).sendMessage(
                eq(telegramChatId),
                eq("I am temporarily unable to generate an AI answer due to an AI provider credit limit. Please contact the group admin."),
                isNull(),
                eq(telegramMessageId)
        );

        var events = adminActivityBuffer.getRecentEvents(10, "COMMAND_FAILED", mockGroup.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).status()).isEqualTo("CREDIT_EXHAUSTED");
    }

    @Test
    @DisplayName("Handles AI provider rate limit with clear retry advice and admin activity recording")
    void handlesAiRateLimitGracefully() {
        MessageDto message = createMessage("/ask When is the release?");

        when(groupService.resolveGroup(any())).thenReturn(mockGroup);
        when(userService.resolveUser(any())).thenReturn(mockUser);

        SearchHit hit = new SearchHit(
                2L, 202L, databaseGroupId, databaseUserId, "bob", "Bob",
                "Release is next Friday.", Instant.now(), 0.1
        );
        when(semanticSearchService.search(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of(hit));

        when(aiService.generateGroundedAnswer(anyString(), anyString()))
                .thenThrow(new com.recallbot.ai.exception.AIProviderRateLimitException("Rate limit 429"));

        handler.handle(message);

        verify(telegramClient).sendMessage(
                eq(telegramChatId),
                eq("The AI service is temporarily busy (rate-limited). Please wait a moment and try your question again."),
                isNull(),
                eq(telegramMessageId)
        );

        var events = adminActivityBuffer.getRecentEvents(10, "COMMAND_FAILED", mockGroup.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).status()).isEqualTo("RATE_LIMITED");
    }

    @Test
    @DisplayName("Handles AI provider unavailability with clear advice and admin activity recording")
    void handlesAiUnavailableGracefully() {
        MessageDto message = createMessage("/ask When is the release?");

        when(groupService.resolveGroup(any())).thenReturn(mockGroup);
        when(userService.resolveUser(any())).thenReturn(mockUser);

        SearchHit hit = new SearchHit(
                2L, 202L, databaseGroupId, databaseUserId, "bob", "Bob",
                "Release is next Friday.", Instant.now(), 0.1
        );
        when(semanticSearchService.search(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of(hit));

        when(aiService.generateGroundedAnswer(anyString(), anyString()))
                .thenThrow(new com.recallbot.ai.exception.AIProviderUnavailableException("OpenRouter 500"));

        handler.handle(message);

        verify(telegramClient).sendMessage(
                eq(telegramChatId),
                eq("The AI service is temporarily unavailable. Please try again shortly."),
                isNull(),
                eq(telegramMessageId)
        );

        var events = adminActivityBuffer.getRecentEvents(10, "COMMAND_FAILED", mockGroup.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    @DisplayName("Records successful command execution with stage timings in admin activity buffer")
    void recordsActivityOnSuccess() {
        MessageDto message = createMessage("/ask When is the release?");

        when(groupService.resolveGroup(any())).thenReturn(mockGroup);
        when(userService.resolveUser(any())).thenReturn(mockUser);

        SearchHit hit = new SearchHit(
                2L, 202L, databaseGroupId, databaseUserId, "bob", "Bob",
                "Release is next Friday.", Instant.now(), 0.1
        );
        when(semanticSearchService.search(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of(hit));

        when(aiService.generateGroundedAnswer(anyString(), anyString()))
                .thenReturn("Release is next Friday [Msg #202].");

        handler.handle(message);

        var events = adminActivityBuffer.getRecentEvents(10, "COMMAND_EXECUTED", mockGroup.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).status()).isEqualTo("SUCCESS");
        assertThat(events.get(0).details()).contains("Processed /ask in");
        assertThat(events.get(0).details()).contains("hits=1");
    }

    @Test
    @DisplayName("Rejects /ask when invoked from private (non-group) chat")
    void rejectsPrivateChat() {
        ChatDto privateChat = new ChatDto(12345L, "private", null, "alice");
        UserDto user = new UserDto(12345L, false, "alice", "Alice", null);
        MessageDto message = new MessageDto(telegramMessageId, user, privateChat, 1700000000L, "/ask something", null, null);

        handler.handle(message);

        verify(semanticSearchService, never()).search(anyLong(), anyString(), anyInt());
        verify(telegramClient).sendMessage(
                eq(12345L),
                contains("can only be used within a group chat"),
                isNull(),
                eq(telegramMessageId)
        );
    }

    @Test
    @DisplayName("Sanitizes hallucinated citations from AI answer before sending")
    void sanitizesHallucinatedCitations() {
        MessageDto message = createMessage("/ask What did Alice say?");

        when(groupService.resolveGroup(any())).thenReturn(mockGroup);
        when(userService.resolveUser(any())).thenReturn(mockUser);

        SearchHit hit = new SearchHit(
                3L, 101L, databaseGroupId, databaseUserId, "alice", "Alice",
                "Hello!", Instant.now(), 0.1
        );
        when(semanticSearchService.search(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of(hit));

        // AI returns valid citation 101 and hallucinated citation 9999
        when(aiService.generateGroundedAnswer(anyString(), anyString()))
                .thenReturn("Alice said Hello [Msg #101] and Charlie agreed [Msg #9999].");

        handler.handle(message);

        // Msg #9999 is stripped
        verify(telegramClient).sendMessage(
                eq(telegramChatId),
                eq("Alice said Hello [Msg #101] and Charlie agreed."),
                isNull(),
                eq(telegramMessageId)
        );
    }

    @Test
    @DisplayName("Handles empty /recall command without question by sending usage guidance")
    void handlesEmptyRecallCommand() {
        MessageDto message = createMessage("/recall   ");

        handler.handle(message);

        verify(semanticSearchService, never()).search(anyLong(), anyString(), anyInt());
        verify(aiService, never()).generateGroundedAnswer(anyString(), anyString());

        verify(telegramClient).sendMessage(
                eq(telegramChatId),
                contains("Please provide a question after /recall"),
                isNull(),
                eq(telegramMessageId)
        );
    }

    @Test
    @DisplayName("Processes /recall end-to-end retrieving stored memories and returning grounded response")
    void processesRecallCommandSuccessfully() {
        MessageDto message = createMessage("/recall what database did we decide to use?");

        when(groupService.resolveGroup(any())).thenReturn(mockGroup);
        when(userService.resolveUser(any())).thenReturn(mockUser);

        SearchHit hit = new SearchHit(
                10L, 66L, databaseGroupId, databaseUserId, "YASH1thhh", "YASH1TH",
                "We decided to use PostgreSQL with pgvector for RecallMemoryBot.", Instant.parse("2026-09-06T08:44:53Z"), 0.02
        );
        when(semanticSearchService.search(eq(databaseGroupId), eq("what database did we decide to use?"), eq(10)))
                .thenReturn(List.of(hit));

        when(aiService.generateGroundedAnswer(anyString(), anyString()))
                .thenReturn("You decided to use PostgreSQL with pgvector for RecallMemoryBot [Msg #66].");

        handler.handle(message);

        verify(semanticSearchService).search(eq(databaseGroupId), eq("what database did we decide to use?"), eq(10));
        verify(telegramClient).sendChatAction(eq(telegramChatId), eq("typing"));
        verify(telegramClient).sendMessage(
                eq(telegramChatId),
                eq("You decided to use PostgreSQL with pgvector for RecallMemoryBot [Msg #66]."),
                isNull(),
                eq(telegramMessageId)
        );
    }

    @Test
    @DisplayName("Handles zero results for /recall gracefully without invoking AI")
    void handlesZeroResultsForRecall() {
        MessageDto message = createMessage("/recall nonexistent topic?");

        when(groupService.resolveGroup(any())).thenReturn(mockGroup);
        when(userService.resolveUser(any())).thenReturn(mockUser);
        when(semanticSearchService.search(eq(databaseGroupId), eq("nonexistent topic?"), eq(10)))
                .thenReturn(Collections.emptyList());

        handler.handle(message);

        verify(aiService, never()).generateGroundedAnswer(anyString(), anyString());
        verify(telegramClient).sendMessage(
                eq(telegramChatId),
                eq("I don't have enough conversation history in this group to answer that question."),
                isNull(),
                eq(telegramMessageId)
        );
    }

    @Test
    @DisplayName("User rate limit throttling rejects 4th request within 1 minute without invoking search or AI")
    void userRateLimitThrottlesExcessiveQueries() {
        when(groupService.resolveGroup(any())).thenReturn(mockGroup);
        when(userService.resolveUser(any())).thenReturn(mockUser);
        when(semanticSearchService.search(anyLong(), anyString(), anyInt()))
                .thenReturn(Collections.emptyList());

        MessageDto message = createMessage("/ask query number?");

        // 3 requests pass under limit (limit = 3 per min)
        handler.handle(message);
        handler.handle(message);
        handler.handle(message);

        verify(semanticSearchService, times(3)).search(anyLong(), anyString(), anyInt());

        // 4th request exceeds rate limit
        handler.handle(message);

        // Search was NOT called a 4th time
        verify(semanticSearchService, times(3)).search(anyLong(), anyString(), anyInt());
        verify(aiService, never()).generateGroundedAnswer(anyString(), anyString());

        // Throttling warning sent to user
        verify(telegramClient).sendMessage(
                eq(telegramChatId),
                contains("You are asking questions too frequently in this group"),
                isNull(),
                eq(telegramMessageId)
        );
    }

    @Test
    @DisplayName("Multilingual: Handles Hinglish question and verifies prompt contains multilingual grounding rules and user query")
    void handlesHinglishQuestion() {
        MessageDto message = createMessage("/ask bhai postgresql kab decide kiya tha?");

        when(groupService.resolveGroup(any())).thenReturn(mockGroup);
        when(userService.resolveUser(any())).thenReturn(mockUser);

        SearchHit hit = new SearchHit(
                1L, 101L, databaseGroupId, databaseUserId, "bob", "Bob",
                "We chose PostgreSQL on Tuesday.", Instant.parse("2026-09-01T10:00:00Z"), 0.05
        );
        when(semanticSearchService.search(eq(databaseGroupId), eq("bhai postgresql kab decide kiya tha?"), eq(10)))
                .thenReturn(List.of(hit));

        when(aiService.generateGroundedAnswer(anyString(), anyString()))
                .thenReturn("PostgreSQL Tuesday ko choose kiya gaya tha [Msg #101].");

        handler.handle(message);

        ArgumentCaptor<String> sysPromptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiService).generateGroundedAnswer(sysPromptCaptor.capture(), userPromptCaptor.capture());

        assertThat(sysPromptCaptor.getValue()).contains("LANGUAGE AND MULTILINGUAL RULES");
        assertThat(sysPromptCaptor.getValue()).contains("Hinglish");
        assertThat(userPromptCaptor.getValue()).contains("User Question: bhai postgresql kab decide kiya tha?");

        verify(telegramClient).sendMessage(
                eq(telegramChatId),
                eq("PostgreSQL Tuesday ko choose kiya gaya tha [Msg #101]."),
                isNull(),
                eq(telegramMessageId)
        );
    }

    @Test
    @DisplayName("Multilingual: Handles Telugu script question seamlessly")
    void handlesTeluguScriptQuestion() {
        MessageDto message = createMessage("/ask మనం పోస్ట్‌గ్రేస్ ఎస్క్యూఎల్ ఎప్పుడు నిర్ణయించాము?");

        when(groupService.resolveGroup(any())).thenReturn(mockGroup);
        when(userService.resolveUser(any())).thenReturn(mockUser);

        SearchHit hit = new SearchHit(
                1L, 101L, databaseGroupId, databaseUserId, "bob", "Bob",
                "We chose PostgreSQL on Tuesday.", Instant.parse("2026-09-01T10:00:00Z"), 0.05
        );
        when(semanticSearchService.search(eq(databaseGroupId), eq("మనం పోస్ట్‌గ్రేస్ ఎస్క్యూఎల్ ఎప్పుడు నిర్ణయించాము?"), eq(10)))
                .thenReturn(List.of(hit));

        when(aiService.generateGroundedAnswer(anyString(), anyString()))
                .thenReturn("మనం మంగళవారం పోస్ట్‌గ్రేస్ ఎస్క్యూఎల్ ఎంచుకున్నాము [Msg #101].");

        handler.handle(message);

        verify(telegramClient).sendMessage(
                eq(telegramChatId),
                eq("మనం మంగళవారం పోస్ట్‌గ్రేస్ ఎస్క్యూఎల్ ఎంచుకున్నాము [Msg #101]."),
                isNull(),
                eq(telegramMessageId)
        );
    }

    @Test
    @DisplayName("Multilingual: Handles Romanized Telugu question seamlessly")
    void handlesRomanizedTeluguQuestion() {
        MessageDto message = createMessage("/ask manam postgresql eppudu decide chesam?");

        when(groupService.resolveGroup(any())).thenReturn(mockGroup);
        when(userService.resolveUser(any())).thenReturn(mockUser);

        SearchHit hit = new SearchHit(
                1L, 101L, databaseGroupId, databaseUserId, "bob", "Bob",
                "We chose PostgreSQL on Tuesday.", Instant.parse("2026-09-01T10:00:00Z"), 0.05
        );
        when(semanticSearchService.search(eq(databaseGroupId), eq("manam postgresql eppudu decide chesam?"), eq(10)))
                .thenReturn(List.of(hit));

        when(aiService.generateGroundedAnswer(anyString(), anyString()))
                .thenReturn("Manam Tuesday roju PostgreSQL decide chesam [Msg #101].");

        handler.handle(message);

        verify(telegramClient).sendMessage(
                eq(telegramChatId),
                eq("Manam Tuesday roju PostgreSQL decide chesam [Msg #101]."),
                isNull(),
                eq(telegramMessageId)
        );
    }

    private MessageDto createMessage(String text) {
        ChatDto chat = new ChatDto(telegramChatId, "supergroup", "Test Group", null);
        UserDto user = new UserDto(telegramUserId, false, "alice", "Alice", null);
        return new MessageDto(telegramMessageId, user, chat, 1700000000L, text, null, null);
    }
}
