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
import com.recallbot.security.RateLimitResult;
import com.recallbot.security.RateLimiter;
import com.recallbot.security.TenantContext;
import com.recallbot.telegram.TelegramClient;
import com.recallbot.telegram.dto.ChatDto;
import com.recallbot.telegram.dto.MessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Handles the /ask command, orchestrating group verification, semantic retrieval,
 * prompt construction, grounded AI answer generation, and Telegram delivery.
 */
@Component
public class AskCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(AskCommandHandler.class);
    private static final int MAX_TELEGRAM_MESSAGE_LENGTH = 4000;

    private final GroupService groupService;
    private final UserService userService;
    private final GroupMembershipService groupMembershipService;
    private final SemanticSearchService semanticSearchService;
    private final AIService aiService;
    private final PromptBuilder promptBuilder;
    private final CitationValidator citationValidator;
    private final TelegramClient telegramClient;
    private final RecallProperties properties;
    private final RateLimiter rateLimiter;

    public AskCommandHandler(
            GroupService groupService,
            UserService userService,
            GroupMembershipService groupMembershipService,
            SemanticSearchService semanticSearchService,
            AIService aiService,
            PromptBuilder promptBuilder,
            CitationValidator citationValidator,
            TelegramClient telegramClient,
            RecallProperties properties,
            RateLimiter rateLimiter
    ) {
        this.groupService = groupService;
        this.userService = userService;
        this.groupMembershipService = groupMembershipService;
        this.semanticSearchService = semanticSearchService;
        this.aiService = aiService;
        this.promptBuilder = promptBuilder;
        this.citationValidator = citationValidator;
        this.telegramClient = telegramClient;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean canHandle(String command) {
        return "/ask".equalsIgnoreCase(command) || "/recall".equalsIgnoreCase(command);
    }

    @Override
    public void handle(MessageDto message) {
        if (message == null) {
            return;
        }

        ChatDto chat = message.chat();
        if (chat == null || chat.id() == null) {
            log.warn("Cannot handle /ask command without valid chat");
            return;
        }

        Long chatId = chat.id();
        Long replyToMessageId = message.messageId();

        // 1. Validate Group context
        if (!chat.isGroupOrSupergroup()) {
            telegramClient.sendMessage(chatId, "The /ask command can only be used within a group chat.", null, replyToMessageId);
            return;
        }

        // 2. Extract and validate question
        String content = message.extractContent();
        String question = extractQuestion(content);

        if (question == null || question.length() < 3) {
            String cmd = (content != null && content.toLowerCase().startsWith("/recall")) ? "/recall" : "/ask";
            telegramClient.sendMessage(
                    chatId,
                    "Please provide a question after " + cmd + ". Example: " + cmd + " when did we decide to use PostgreSQL?",
                    null,
                    replyToMessageId
            );
            return;
        }

        // 3. Send typing indicator for user responsiveness
        telegramClient.sendChatAction(chatId, "typing");

        try {
            // 4. Resolve group and user identities, ensuring membership
            GroupEntity group = groupService.resolveGroup(chat);
            Long databaseUserId = null;
            if (message.from() != null) {
                UserEntity user = userService.resolveUser(message.from());
                groupMembershipService.ensureMembership(group, user, GroupRole.MEMBER);
                databaseUserId = user.getId();
            }

            // 4b. Enforce in-memory sliding-window rate limit per (groupId, userId) and per groupId
            if (databaseUserId != null) {
                RateLimitResult rateCheck = rateLimiter.tryAcquire(group.getId(), databaseUserId);
                if (!rateCheck.isAllowed()) {
                    String cmd = (content != null && content.toLowerCase().startsWith("/recall")) ? "recall" : "ask";
                    log.info("Rate limit exceeded for /{} in group_id={}, user_id={}, reason={}, retryAfterSec={}",
                            cmd, group.getId(), databaseUserId, rateCheck.reason(), rateCheck.retryAfterSeconds());
                    telegramClient.sendMessage(chatId, rateCheck.errorMessage(), null, replyToMessageId);
                    return;
                }
            }

            try (TenantContext.TenantScope ignored = TenantContext.with(group.getId())) {
                // 5. Execute semantic search with group isolation
                int topK = (properties.search() != null && properties.search().maxCandidates() > 0)
                        ? properties.search().maxCandidates()
                        : 10;

                List<SearchHit> hits = semanticSearchService.search(group.getId(), question, topK);

                // 6. Handle empty/no-result situation deterministically
                if (hits == null || hits.isEmpty()) {
                    log.debug("No semantic memory hits found for group_id={}, query_len={}", group.getId(), question.length());
                    telegramClient.sendMessage(
                            chatId,
                            "I don't have enough conversation history in this group to answer that question.",
                            null,
                            replyToMessageId
                    );
                    return;
                }

                // 7. Assemble grounded prompt with injection defense
                String systemPrompt = promptBuilder.buildSystemPrompt();
                String userPrompt = promptBuilder.buildUserPrompt(question, hits);

                // 8. Generate answer via AI Service
                String rawAnswer = aiService.generateGroundedAnswer(systemPrompt, userPrompt);

                // 9. Sanitize and validate citations
                String sanitizedAnswer = citationValidator.validateAndSanitize(rawAnswer, hits);

                // 10. Enforce Telegram message size bounds
                if (sanitizedAnswer.length() > MAX_TELEGRAM_MESSAGE_LENGTH) {
                    sanitizedAnswer = sanitizedAnswer.substring(0, MAX_TELEGRAM_MESSAGE_LENGTH - 4) + "...";
                }

                // 11. Dispatch answer back to Telegram
                telegramClient.sendMessage(chatId, sanitizedAnswer, null, replyToMessageId);
            }

        } catch (Exception e) {
            log.error("Failed to process /ask command for group chat_id={}: {}", chatId, e.getMessage(), e);
            telegramClient.sendMessage(
                    chatId,
                    "I encountered an error retrieving memories or generating an answer. Please try again later.",
                    null,
                    replyToMessageId
            );
        }
    }

    private String extractQuestion(String content) {
        if (content == null) {
            return null;
        }
        String stripped = content.stripLeading();
        int spaceIdx = stripped.indexOf(' ');
        if (spaceIdx == -1 || spaceIdx == stripped.length() - 1) {
            return null;
        }
        return stripped.substring(spaceIdx + 1).trim();
    }
}
