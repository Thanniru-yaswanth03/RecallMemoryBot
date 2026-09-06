package com.recallbot.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.recallbot.ai.AIService;
import com.recallbot.ai.citation.CitationValidator;
import com.recallbot.ai.prompt.PromptBuilder;
import com.recallbot.config.properties.RecallProperties;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipService;
import com.recallbot.core.group.GroupService;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserService;
import com.recallbot.search.SemanticSearchService;
import com.recallbot.telegram.TelegramClient;
import com.recallbot.telegram.command.AskCommandHandler;
import com.recallbot.telegram.dto.ChatDto;
import com.recallbot.telegram.dto.MessageDto;
import com.recallbot.telegram.dto.UserDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSanitizationSecurityTest {

    private static final String SENSITIVE_TOKEN = "123456789:ABCdefGhIjkLmNoPqRsTuVwXyZ123456";
    private static final String SENSITIVE_API_KEY = "sk-or-v1-9876543210abcdef9876543210";
    private static final String SENSITIVE_WEBHOOK_SECRET = "super-secret-telegram-webhook-token-42";
    private static final String SENSITIVE_RAW_QUESTION = "What is the secret master password for AWS?";

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

    private ListAppender<ILoggingEvent> listAppender;
    private Logger recallBotLogger;
    private AskCommandHandler askCommandHandler;

    @BeforeEach
    void setUp() {
        // Attach in-memory appender to root com.recallbot logger
        recallBotLogger = (Logger) LoggerFactory.getLogger("com.recallbot");
        listAppender = new ListAppender<>();
        listAppender.start();
        recallBotLogger.addAppender(listAppender);

        RecallProperties properties = new RecallProperties(
                new RecallProperties.Telegram("webhook", "recall_bot", SENSITIVE_TOKEN, SENSITIVE_WEBHOOK_SECRET, null),
                new RecallProperties.Ai(SENSITIVE_API_KEY, "claude", "openai/text-embedding-3-small", 1536, 30, 800, 0.2),
                new RecallProperties.Search(10, 60, 2500),
                new RecallProperties.RateLimit(3, 10)
        );

        RateLimiter rateLimiter = new RateLimiter(properties);
        askCommandHandler = new AskCommandHandler(
                groupService,
                userService,
                groupMembershipService,
                semanticSearchService,
                aiService,
                new PromptBuilder(),
                new CitationValidator(),
                telegramClient,
                properties,
                rateLimiter
        );
    }

    @AfterEach
    void tearDown() {
        recallBotLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    @Test
    @DisplayName("LogSanitizer successfully redacts Telegram tokens, Bearer keys, and secret parameters")
    void logSanitizerMasksSensitivePatterns() {
        String raw = "Connecting with token 123456789:ABCdefGhIjkLmNoPqRsTuVwXyZ123456 and Bearer sk-or-v1-abcdef1234567890 password=supersecret";
        String sanitized = LogSanitizer.maskSecrets(raw);

        assertThat(sanitized).doesNotContain("123456789:ABCdefGhIjkLmNoPqRsTuVwXyZ123456");
        assertThat(sanitized).doesNotContain("sk-or-v1-abcdef1234567890");
        assertThat(sanitized).doesNotContain("supersecret");
        assertThat(sanitized).contains("[REDACTED_TELEGRAM_TOKEN]");
        assertThat(sanitized).contains("[REDACTED_API_KEY]");
        assertThat(sanitized).contains("password=[REDACTED]");
    }

    @Test
    @DisplayName("Executing /ask command does not log raw question text, bot tokens, or API secrets")
    void askCommandExecutionDoesNotLogSensitiveData() {
        ChatDto chat = new ChatDto(-100888L, "supergroup", "Secure Group", null);
        UserDto userDto = new UserDto(9999L, false, "alice", "Alice", null);
        MessageDto message = new MessageDto(501L, userDto, chat, 1700000000L, "/ask " + SENSITIVE_RAW_QUESTION, null, null);

        GroupEntity group = new GroupEntity(-100888L, "Secure Group");
        group.setId(88L);
        UserEntity user = new UserEntity(9999L, "alice", "Alice", null);
        user.setId(99L);

        when(groupService.resolveGroup(chat)).thenReturn(group);
        when(userService.resolveUser(userDto)).thenReturn(user);
        when(semanticSearchService.search(anyLong(), anyString(), anyInt())).thenReturn(Collections.emptyList());

        askCommandHandler.handle(message);

        List<ILoggingEvent> events = listAppender.list;
        assertThat(events).isNotEmpty();

        for (ILoggingEvent event : events) {
            String formattedMessage = event.getFormattedMessage();

            // Secrets must never be logged
            assertThat(formattedMessage)
                    .as("Log must not contain bot token")
                    .doesNotContain(SENSITIVE_TOKEN);

            assertThat(formattedMessage)
                    .as("Log must not contain API key")
                    .doesNotContain(SENSITIVE_API_KEY);

            assertThat(formattedMessage)
                    .as("Log must not contain webhook secret")
                    .doesNotContain(SENSITIVE_WEBHOOK_SECRET);

            // Verbatim confidential question text must not appear in debug/info logs
            assertThat(formattedMessage)
                    .as("Log must not contain raw question text")
                    .doesNotContain(SENSITIVE_RAW_QUESTION);
        }
    }
}
