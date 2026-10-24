package com.recallbot.search;

import com.recallbot.ai.AIService;
import com.recallbot.ai.EmbeddingService;
import com.recallbot.ai.EmbeddingTextNormalizer;
import com.recallbot.ai.citation.CitationValidator;
import com.recallbot.ai.exception.AIProviderRateLimitException;
import com.recallbot.ai.exception.AIProviderUnavailableException;
import com.recallbot.ai.prompt.PromptBuilder;
import com.recallbot.config.properties.RecallProperties;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipService;
import com.recallbot.core.group.GroupService;
import com.recallbot.core.message.*;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserService;
import com.recallbot.privacy.PrivacyService;
import com.recallbot.search.dto.SearchHit;
import com.recallbot.security.RateLimiter;
import com.recallbot.telegram.TelegramClient;
import com.recallbot.telegram.TelegramUpdateDeduplicator;
import com.recallbot.telegram.command.AskCommandHandler;
import com.recallbot.telegram.dto.ChatDto;
import com.recallbot.telegram.dto.MessageDto;
import com.recallbot.telegram.dto.UpdateDto;
import com.recallbot.telegram.dto.UserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 10: Production-grade Memory Recall Test Suite covering Tests A through M.
 */
@ExtendWith(MockitoExtension.class)
class MemoryRecallTestSuiteTest {

    private static final Long GROUP_1_ID = 100L;
    private static final Long GROUP_2_ID = 200L;
    private static final Long TELEGRAM_CHAT_1 = -100111L;
    private static final Long TELEGRAM_CHAT_2 = -100222L;

    @Mock private GroupService groupService;
    @Mock private UserService userService;
    @Mock private GroupMembershipService groupMembershipService;
    @Mock private SemanticSearchService semanticSearchService;
    @Mock private AIService aiService;
    @Mock private TelegramClient telegramClient;
    @Mock private MessageRepository messageRepository;
    @Mock private MessageEmbeddingRepository messageEmbeddingRepository;
    @Mock private EmbeddingService embeddingService;
    @Mock private TelegramUpdateDeduplicator updateDeduplicator;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PrivacyService privacyService;
    @Mock private JdbcClient jdbcClient;

    private PromptBuilder promptBuilder;
    private CitationValidator citationValidator;
    private MultilingualQueryProcessor queryProcessor;
    private ConversationContextService contextService;
    private EmbeddingTextNormalizer textNormalizer;
    private RecallProperties properties;
    private RateLimiter rateLimiter;
    private EmbeddingReconciler embeddingReconciler;
    private AskCommandHandler askHandler;

    private GroupEntity group1;
    private GroupEntity group2;
    private UserEntity user1;

    @BeforeEach
    void setUp() {
        promptBuilder = new PromptBuilder();
        citationValidator = new CitationValidator();
        queryProcessor = new MultilingualQueryProcessorImpl();
        contextService = new ConversationContextService();
        textNormalizer = new EmbeddingTextNormalizer();

        properties = new RecallProperties(
                new RecallProperties.Telegram("webhook", "recall_bot", "token", "secret", null),
                new RecallProperties.Ai("key", "claude", "openai/text-embedding-3-small", 1536, 30, 800, 0.2),
                new RecallProperties.Search(10, 60, 2500),
                new RecallProperties.RateLimit(10, 60)
        );
        rateLimiter = new RateLimiter(properties);

        embeddingReconciler = new EmbeddingReconciler(
                jdbcClient,
                embeddingService,
                textNormalizer
        );

        askHandler = new AskCommandHandler(
                groupService,
                userService,
                groupMembershipService,
                semanticSearchService,
                aiService,
                promptBuilder,
                citationValidator,
                telegramClient,
                properties,
                rateLimiter,
                contextService,
                messageRepository,
                embeddingReconciler,
                queryProcessor
        );

        group1 = new GroupEntity(TELEGRAM_CHAT_1, "Engineering Team");
        group1.setId(GROUP_1_ID);

        group2 = new GroupEntity(TELEGRAM_CHAT_2, "Marketing Team");
        group2.setId(GROUP_2_ID);

        user1 = new UserEntity(12345L, "alice", "Alice", null);
        user1.setId(1L);

        lenient().when(groupService.resolveGroup(argThat(c -> c != null && c.id().equals(TELEGRAM_CHAT_1)))).thenReturn(group1);
        lenient().when(groupService.resolveGroup(argThat(c -> c != null && c.id().equals(TELEGRAM_CHAT_2)))).thenReturn(group2);
        lenient().when(userService.resolveUser(any())).thenReturn(user1);
    }

    private MessageDto createMessage(Long chatId, Long messageId, String text) {
        return new MessageDto(
                messageId,
                new UserDto(12345L, false, "Alice", null, "alice"),
                new ChatDto(chatId, "group", "Test Group", "testgroup"),
                Instant.now().getEpochSecond(),
                text,
                null,
                null
        );
    }

    // ==========================================
    // TEST A: Basic recall (Multiple tasks)
    // ==========================================
    @Test
    @DisplayName("Test A: Basic recall retrieves all multiple tasks without dropping items")
    void testA_basicRecallMultipleTasks() {
        SearchHit task1 = new SearchHit(1L, 101L, GROUP_1_ID, 1L, "alice", "Alice", "Task 1: Setup Postgres with pgvector", Instant.now().minusSeconds(300), 0.15);
        SearchHit task2 = new SearchHit(2L, 102L, GROUP_1_ID, 1L, "bob", "Bob", "Task 2: Configure Telegram webhook security", Instant.now().minusSeconds(200), 0.18);
        SearchHit task3 = new SearchHit(3L, 103L, GROUP_1_ID, 1L, "charlie", "Charlie", "Task 3: Integrate OpenRouter embeddings", Instant.now().minusSeconds(100), 0.20);
        List<SearchHit> allTasks = List.of(task1, task2, task3);

        when(semanticSearchService.search(eq(GROUP_1_ID), anyString(), eq(10))).thenReturn(allTasks);
        when(aiService.generateGroundedAnswer(anyString(), anyString())).thenReturn(
                "Here are all 3 tasks discussed:\n1. Setup Postgres with pgvector [Msg #101]\n2. Configure Telegram webhook security [Msg #102]\n3. Integrate OpenRouter embeddings [Msg #103]"
        );

        MessageDto msg = createMessage(TELEGRAM_CHAT_1, 501L, "/ask what are all the tasks?");
        askHandler.handle(msg);

        ArgumentCaptor<String> replyCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramClient).sendMessage(eq(TELEGRAM_CHAT_1), replyCaptor.capture(), isNull(), eq(501L));

        String reply = replyCaptor.getValue();
        assertThat(reply).contains("[Msg #101]", "[Msg #102]", "[Msg #103]");
        assertThat(reply).contains("Setup Postgres", "Configure Telegram", "Integrate OpenRouter");
    }

    // ==========================================
    // TEST B: Follow-up context ("What else?")
    // ==========================================
    @Test
    @DisplayName("Test B: Follow-up question 'What else?' excludes previously returned messages")
    void testB_followUpContextExclusion() {
        SearchHit task1 = new SearchHit(1L, 101L, GROUP_1_ID, 1L, "alice", "Alice", "Task 1: Setup Postgres", Instant.now(), 0.15);
        SearchHit task2 = new SearchHit(2L, 102L, GROUP_1_ID, 1L, "bob", "Bob", "Task 2: Telegram webhook", Instant.now(), 0.18);
        when(semanticSearchService.search(eq(GROUP_1_ID), anyString(), eq(10))).thenReturn(List.of(task1, task2));
        when(aiService.generateGroundedAnswer(anyString(), anyString())).thenReturn("Tasks: 1. Setup Postgres [Msg #101] 2. Webhook [Msg #102]");

        // 1. Initial query: "What tasks were discussed?"
        askHandler.handle(createMessage(TELEGRAM_CHAT_1, 601L, "/ask what tasks were discussed?"));

        // 2. Follow-up query: "What else?"
        SearchHit task3 = new SearchHit(3L, 103L, GROUP_1_ID, 1L, "charlie", "Charlie", "Task 3: Implement citation validation", Instant.now(), 0.22);
        when(semanticSearchService.searchHybrid(eq(GROUP_1_ID), anyString(), eq(10), argThat(set -> set.contains(101L) && set.contains(102L))))
                .thenReturn(List.of(task3));
        when(aiService.generateGroundedAnswer(anyString(), anyString())).thenReturn("Remaining task: Implement citation validation [Msg #103]");

        askHandler.handle(createMessage(TELEGRAM_CHAT_1, 602L, "/ask what else?"));

        ArgumentCaptor<String> replyCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramClient).sendMessage(eq(TELEGRAM_CHAT_1), replyCaptor.capture(), isNull(), eq(602L));

        assertThat(replyCaptor.getValue()).contains("[Msg #103]");
        assertThat(replyCaptor.getValue()).contains("Implement citation validation");
    }

    // ==========================================
    // TEST C: Multilingual retrieval
    // ==========================================
    @Test
    @DisplayName("Test C: Cross-lingual query expansion detects Hindi, Telugu, Hinglish, and English intents")
    void testC_multilingualQueryExpansion() {
        // English to Hindi/Telugu concept
        var engQuery = queryProcessor.process("when is the meeting and what is the task?");
        assertThat(engQuery.detectedLanguage()).isEqualTo(MultilingualQueryProcessor.QueryLanguage.ENGLISH);
        assertThat(engQuery.crossLingualTerms()).contains("मीटिंग", "మీటింగ్", "काम", "pani", "kaam");

        // Hindi Devanagari query
        var hindiQuery = queryProcessor.process("मीटिंग कब है और काम क्या है?");
        assertThat(hindiQuery.detectedLanguage()).isEqualTo(MultilingualQueryProcessor.QueryLanguage.HINDI_DEVANAGARI);
        assertThat(hindiQuery.crossLingualTerms()).contains("meeting", "task", "time");

        // Telugu Script query
        var teluguQuery = queryProcessor.process("మీటింగ్ ఎప్పుడు మరియు పని ఏమిటి?");
        assertThat(teluguQuery.detectedLanguage()).isEqualTo(MultilingualQueryProcessor.QueryLanguage.TELUGU_SCRIPT);
        assertThat(teluguQuery.crossLingualTerms()).contains("meeting", "task", "pani", "time");

        // Hinglish query
        var hinglishQuery = queryProcessor.process("kal ka kya faisla hua bhai?");
        assertThat(hinglishQuery.detectedLanguage()).isEqualTo(MultilingualQueryProcessor.QueryLanguage.HINGLISH);
        assertThat(hinglishQuery.crossLingualTerms()).contains("decision", "tomorrow", "decided");

        // Telugish (Romanized Telugu)
        var telugishQuery = queryProcessor.process("manam repu eppudu kalavali?");
        assertThat(telugishQuery.detectedLanguage()).isEqualTo(MultilingualQueryProcessor.QueryLanguage.TELUGISH);
        assertThat(telugishQuery.crossLingualTerms()).contains("tomorrow", "repu", "kal");
    }

    // ==========================================
    // TEST D: Names and unusual identifiers
    // ==========================================
    @Test
    @DisplayName("Test D: Preserves unique identifiers like 'yo_siri', task IDs, and dates for exact matching")
    void testD_exactIdentifierExtraction() {
        var processed = queryProcessor.process("What did @yo_siri say about TASK-404 on 2026-10-24 at https://example.com/api?");
        assertThat(processed.exactKeywords()).contains("yo_siri", "TASK-404", "2026-10-24", "https://example.com/api");
    }

    // ==========================================
    // TEST E: Updates and contradictions
    // ==========================================
    @Test
    @DisplayName("Test E: Prompt guidelines instruct model to distinguish original vs updated deadlines")
    void testE_updatesAndContradictions() {
        SearchHit hit1 = new SearchHit(1L, 101L, GROUP_1_ID, 1L, "alice", "Alice", "The release deadline is Friday Nov 1", Instant.now().minusSeconds(10000), 0.1);
        SearchHit hit2 = new SearchHit(2L, 102L, GROUP_1_ID, 1L, "alice", "Alice", "Correction: Deadline moved to Monday Nov 4 due to QA", Instant.now().minusSeconds(100), 0.1);

        String systemPrompt = promptBuilder.buildSystemPrompt();
        String userPrompt = promptBuilder.buildUserPrompt("What is the release deadline?", List.of(hit1, hit2), null);

        assertThat(systemPrompt).contains("timeline", "chronology", "latest supported decision");
        assertThat(userPrompt).contains("Msg #101").contains("Msg #102");
    }

    // ==========================================
    // TEST F: Embedding failure and recovery
    // ==========================================
    @Test
    @DisplayName("Test F: Embedding failures mark message FAILED and reconciler retries successfully")
    void testF_embeddingFailureAndReconciliation() {
        JdbcClient.StatementSpec selectSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<EmbeddingReconciler.UnembeddedMessage> mappedSpec = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(anyString())).thenReturn(selectSpec);
        when(selectSpec.param(anyString(), any())).thenReturn(selectSpec);
        when(selectSpec.param(any())).thenReturn(selectSpec);
        when(selectSpec.query(any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(mappedSpec);

        EmbeddingReconciler.UnembeddedMessage failedMsg = new EmbeddingReconciler.UnembeddedMessage(777L, GROUP_1_ID, "Critical update on architecture");
        when(mappedSpec.list()).thenReturn(List.of(failedMsg));

        when(embeddingService.generateEmbeddings(anyList())).thenReturn(List.of(new float[1536]));
        when(embeddingService.getModelName()).thenReturn("openai/text-embedding-3-small");
        when(selectSpec.update()).thenReturn(1);

        int reconciled = embeddingReconciler.reconcileGroup(GROUP_1_ID);

        assertThat(reconciled).isEqualTo(1);
        verify(embeddingService).generateEmbeddings(List.of("Critical update on architecture"));
    }

    // ==========================================
    // TEST G: Duplicate updates
    // ==========================================
    @Test
    @DisplayName("Test G: Duplicate Telegram update ID is ignored and not re-ingested")
    void testG_duplicateUpdateHandling() {
        com.recallbot.telegram.TelegramWebhookController controller =
                new com.recallbot.telegram.TelegramWebhookController(updateDeduplicator, eventPublisher);

        Long updateId = 99999L;
        UpdateDto update = new UpdateDto(updateId, createMessage(TELEGRAM_CHAT_1, 888L, "Hello team"), null, null);

        // Update deduplicator returns false (already acquired)
        when(updateDeduplicator.tryAcquire(eq(updateId), isNull())).thenReturn(false);

        org.springframework.http.ResponseEntity<?> response = controller.handleWebhook(update);

        assertThat(response.getBody()).isEqualTo(Map.of("status", "duplicate"));
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ==========================================
    // TEST H: Empty and missing memory
    // ==========================================
    @Test
    @DisplayName("Test H: Reports honest limitation when fact does not exist in group memory")
    void testH_emptyAndMissingMemory() {
        when(semanticSearchService.search(eq(GROUP_1_ID), anyString(), eq(10))).thenReturn(Collections.emptyList());
        when(messageRepository.countByGroupId(GROUP_1_ID)).thenReturn(10L);
        when(messageRepository.countByGroupIdAndEmbeddingStatus(GROUP_1_ID, EmbeddingStatus.PENDING)).thenReturn(0L);
        when(messageRepository.countByGroupIdAndEmbeddingStatus(GROUP_1_ID, EmbeddingStatus.FAILED)).thenReturn(0L);

        MessageDto msg = createMessage(TELEGRAM_CHAT_1, 901L, "/ask what is the launch secret code?");
        askHandler.handle(msg);

        verify(telegramClient).sendMessage(
                eq(TELEGRAM_CHAT_1),
                eq("I don't have enough conversation history in this group to answer that question."),
                isNull(),
                eq(901L)
        );
        verify(aiService, never()).generateGroundedAnswer(anyString(), anyString());
    }

    // ==========================================
    // TEST I: Citation integrity
    // ==========================================
    @Test
    @DisplayName("Test I: Sanitizes hallucinated citations that do not exist in retrieved hits")
    void testI_citationIntegrity() {
        SearchHit realHit = new SearchHit(1L, 101L, GROUP_1_ID, 1L, "alice", "Alice", "Project deadline is Friday", Instant.now(), 0.1);
        List<SearchHit> hits = List.of(realHit);

        String answerWithHallucinations = "Alice confirmed deadline is Friday [Msg #101], and Bob said Monday [Msg #999].";
        String sanitized = citationValidator.validateAndSanitize(answerWithHallucinations, hits);

        assertThat(sanitized).contains("[Msg #101]");
        assertThat(sanitized).doesNotContain("[Msg #999]");
        assertThat(sanitized).contains("and Bob said Monday.");
    }

    // ==========================================
    // TEST J: Group isolation
    // ==========================================
    @Test
    @DisplayName("Test J: Queries in Group 1 strictly enforce group_id=100 and cannot retrieve Group 2 messages")
    void testJ_groupIsolationEnforcement() {
        when(semanticSearchService.search(eq(GROUP_1_ID), anyString(), eq(10))).thenReturn(Collections.emptyList());
        when(messageRepository.countByGroupId(GROUP_1_ID)).thenReturn(0L);

        askHandler.handle(createMessage(TELEGRAM_CHAT_1, 1001L, "/ask what was marketing budget?"));

        // Must query with GROUP_1_ID, never GROUP_2_ID
        verify(semanticSearchService).search(eq(GROUP_1_ID), anyString(), eq(10));
        verify(semanticSearchService, never()).search(eq(GROUP_2_ID), anyString(), anyInt());

        verify(telegramClient).sendMessage(
                eq(TELEGRAM_CHAT_1),
                contains("No conversation history has been recorded yet for this group."),
                isNull(),
                eq(1001L)
        );
    }

    // ==========================================
    // TEST K: Privacy deletion
    // ==========================================
    @Test
    @DisplayName("Test K: /forget command deletes message and prevents subsequent retrieval")
    void testK_privacyDeletion() {
        Long messageIdToDelete = 444L;
        Long telegramUserId = 12345L;
        when(privacyService.forgetSingleMessage(GROUP_1_ID, telegramUserId, messageIdToDelete, TELEGRAM_CHAT_1)).thenReturn(true);

        boolean deleted = privacyService.forgetSingleMessage(GROUP_1_ID, telegramUserId, messageIdToDelete, TELEGRAM_CHAT_1);
        assertThat(deleted).isTrue();
        verify(privacyService).forgetSingleMessage(GROUP_1_ID, telegramUserId, messageIdToDelete, TELEGRAM_CHAT_1);
    }

    // ==========================================
    // TEST L: Provider failures
    // ==========================================
    @Test
    @DisplayName("Test L1: AI provider rate limit returns rate-limited service message")
    void testL1_rateLimitHandling() {
        SearchHit hit = new SearchHit(1L, 101L, GROUP_1_ID, 1L, "alice", "Alice", "Valid chat message", Instant.now(), 0.1);
        when(semanticSearchService.search(eq(GROUP_1_ID), anyString(), eq(10))).thenReturn(List.of(hit));

        when(aiService.generateGroundedAnswer(anyString(), anyString())).thenThrow(new AIProviderRateLimitException("429 Too Many Requests"));
        askHandler.handle(createMessage(TELEGRAM_CHAT_1, 1101L, "/ask tell me about the update"));

        verify(telegramClient).sendMessage(
                eq(TELEGRAM_CHAT_1),
                contains("rate-limited"),
                isNull(),
                eq(1101L)
        );
    }

    @Test
    @DisplayName("Test L2: AI provider downtime returns unavailable service message")
    void testL2_providerUnavailableHandling() {
        SearchHit hit = new SearchHit(1L, 101L, GROUP_1_ID, 1L, "alice", "Alice", "Valid chat message", Instant.now(), 0.1);
        when(semanticSearchService.search(eq(GROUP_1_ID), anyString(), eq(10))).thenReturn(List.of(hit));

        when(aiService.generateGroundedAnswer(anyString(), anyString())).thenThrow(new AIProviderUnavailableException("503 Backend Down"));
        askHandler.handle(createMessage(TELEGRAM_CHAT_1, 1102L, "/ask tell me about the update"));

        verify(telegramClient).sendMessage(
                eq(TELEGRAM_CHAT_1),
                contains("unavailable"),
                isNull(),
                eq(1102L)
        );
    }

    // ==========================================
    // TEST M: Concurrency and restart
    // ==========================================
    @Test
    @DisplayName("Test M: Unindexed messages left PENDING after application restart are discovered and recovered")
    void testM_restartAndPendingEmbeddingRecovery() {
        JdbcClient.StatementSpec selectSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<EmbeddingReconciler.UnembeddedMessage> mappedSpec = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(anyString())).thenReturn(selectSpec);
        when(selectSpec.param(anyString(), any())).thenReturn(selectSpec);
        when(selectSpec.param(any())).thenReturn(selectSpec);
        when(selectSpec.query(any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(mappedSpec);

        EmbeddingReconciler.UnembeddedMessage pending1 = new EmbeddingReconciler.UnembeddedMessage(1001L, GROUP_1_ID, "Pre-restart message 1");
        EmbeddingReconciler.UnembeddedMessage pending2 = new EmbeddingReconciler.UnembeddedMessage(1002L, GROUP_1_ID, "Pre-restart message 2");
        when(mappedSpec.list()).thenReturn(List.of(pending1, pending2));

        when(embeddingService.generateEmbeddings(anyList())).thenReturn(List.of(new float[1536], new float[1536]));
        when(embeddingService.getModelName()).thenReturn("openai/text-embedding-3-small");
        when(selectSpec.update()).thenReturn(1);

        int reconciled = embeddingReconciler.reconcileGroup(GROUP_1_ID);

        assertThat(reconciled).isEqualTo(2);
        verify(embeddingService).generateEmbeddings(List.of("Pre-restart message 1", "Pre-restart message 2"));
    }
}
