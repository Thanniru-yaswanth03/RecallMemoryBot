package com.recallbot.telegram.command;

import com.recallbot.admin.activity.AdminActivityBuffer;
import com.recallbot.admin.activity.BotActivityEvent;
import com.recallbot.ai.AIService;
import com.recallbot.ai.citation.CitationValidator;
import com.recallbot.ai.exception.AIProviderCreditExhaustedException;
import com.recallbot.ai.exception.AIProviderRateLimitException;
import com.recallbot.ai.exception.AIProviderUnavailableException;
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AdminActivityBuffer adminActivityBuffer;

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

    public void setAdminActivityBuffer(AdminActivityBuffer adminActivityBuffer) {
        this.adminActivityBuffer = adminActivityBuffer;
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

        String cmd = (content != null && content.toLowerCase().startsWith("/recall")) ? "recall" : "ask";
        String reqId = "req-" + (replyToMessageId != null ? replyToMessageId : System.currentTimeMillis());
        long totalStart = System.currentTimeMillis();
        log.info("[{}] Received /{} command in chat_id={}", reqId, cmd, chatId);

        // 3. Send typing indicator for user responsiveness
        telegramClient.sendChatAction(chatId, "typing");

        GroupEntity group = null;
        try {
            long stageStart = System.currentTimeMillis();
            // 4. Resolve group and user identities, ensuring membership
            group = groupService.resolveGroup(chat);
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
                    log.info("[{}] Rate limit exceeded for /{} in group_id={}, user_id={}, reason={}, retryAfterSec={}",
                            reqId, cmd, group.getId(), databaseUserId, rateCheck.reason(), rateCheck.retryAfterSeconds());
                    telegramClient.sendMessage(chatId, rateCheck.errorMessage(), null, replyToMessageId);
                    return;
                }
            }
            long authMs = System.currentTimeMillis() - stageStart;

            try (TenantContext.TenantScope ignored = TenantContext.with(group.getId())) {
                // 5. Execute semantic search with group isolation
                int topK = (properties.search() != null && properties.search().maxCandidates() > 0)
                        ? properties.search().maxCandidates()
                        : 10;

                long searchStart = System.currentTimeMillis();
                List<SearchHit> hits = semanticSearchService.search(group.getId(), question, topK);
                long searchMs = System.currentTimeMillis() - searchStart;

                // 6. Handle empty/no-result situation deterministically
                if (hits == null || hits.isEmpty()) {
                    log.info("[{}] No semantic memory hits found for group_id={}, query_len={}, search={}ms",
                            reqId, group.getId(), question.length(), searchMs);
                    telegramClient.sendMessage(
                            chatId,
                            "I don't have enough conversation history in this group to answer that question.",
                            null,
                            replyToMessageId
                    );
                    return;
                }

                log.debug("[{}] Retrieved {} semantic hits in {}ms for group_id={}", reqId, hits.size(), searchMs, group.getId());

                // 7. Assemble grounded prompt with injection defense
                String systemPrompt = promptBuilder.buildSystemPrompt();
                String userPrompt = promptBuilder.buildUserPrompt(question, hits);

                // 8. Generate answer via AI Service
                long aiStart = System.currentTimeMillis();
                String rawAnswer = aiService.generateGroundedAnswer(systemPrompt, userPrompt);
                long aiMs = System.currentTimeMillis() - aiStart;

                // 9. Sanitize and validate citations
                String sanitizedAnswer = citationValidator.validateAndSanitize(rawAnswer, hits);

                // 10. Enforce Telegram message size bounds
                if (sanitizedAnswer.length() > MAX_TELEGRAM_MESSAGE_LENGTH) {
                    sanitizedAnswer = sanitizedAnswer.substring(0, MAX_TELEGRAM_MESSAGE_LENGTH - 4) + "...";
                }

                // 11. Dispatch answer back to Telegram
                long tgStart = System.currentTimeMillis();
                telegramClient.sendMessage(chatId, sanitizedAnswer, null, replyToMessageId);
                long tgMs = System.currentTimeMillis() - tgStart;

                long totalDurationMs = System.currentTimeMillis() - totalStart;
                log.info("[{}] Successfully processed /{} for group_id={} in {}ms (auth={}ms, search={}ms, ai={}ms, telegram={}ms, hits={})",
                        reqId, cmd, group.getId(), totalDurationMs, authMs, searchMs, aiMs, tgMs, hits.size());

                if (adminActivityBuffer != null) {
                    adminActivityBuffer.recordEvent(new BotActivityEvent(
                            reqId,
                            java.time.Instant.now(),
                            "COMMAND_EXECUTED",
                            group.getId(),
                            group.getTitle(),
                            "SUCCESS",
                            totalDurationMs,
                            String.format("Processed /%s in %dms (auth=%dms, search=%dms, ai=%dms, tg=%dms, hits=%d)",
                                    cmd, totalDurationMs, authMs, searchMs, aiMs, tgMs, hits.size())
                    ));
                }
            }

        } catch (Exception e) {
            long failedDurationMs = System.currentTimeMillis() - totalStart;
            log.error("[{}] Failed to process /{} command for group chat_id={} after {}ms: {}",
                    reqId, cmd, chatId, failedDurationMs, e.getMessage(), e);

            String userMessage;
            String eventStatus = "FAILED";

            if (e instanceof AIProviderCreditExhaustedException || hasCause(e, AIProviderCreditExhaustedException.class)) {
                userMessage = "I am temporarily unable to generate an AI answer due to an AI provider credit limit. Please contact the group admin.";
                eventStatus = "CREDIT_EXHAUSTED";
            } else if (e instanceof AIProviderRateLimitException || hasCause(e, AIProviderRateLimitException.class)) {
                userMessage = "The AI service is temporarily busy (rate-limited). Please wait a moment and try your question again.";
                eventStatus = "RATE_LIMITED";
            } else if (e instanceof AIProviderUnavailableException || hasCause(e, AIProviderUnavailableException.class)) {
                userMessage = "The AI service is temporarily unavailable. Please try again shortly.";
                eventStatus = "UNAVAILABLE";
            } else {
                userMessage = "I encountered an error retrieving memories or generating an answer. Please try again later.";
            }

            if (adminActivityBuffer != null) {
                adminActivityBuffer.recordEvent(new BotActivityEvent(
                        reqId,
                        java.time.Instant.now(),
                        "COMMAND_FAILED",
                        group != null ? group.getId() : null,
                        group != null ? group.getTitle() : null,
                        eventStatus,
                        failedDurationMs,
                        String.format("Failed /%s after %dms: %s", cmd, failedDurationMs, e.getMessage())
                ));
            }

            telegramClient.sendMessage(
                    chatId,
                    userMessage,
                    null,
                    replyToMessageId
            );
        }
    }

    private boolean hasCause(Throwable t, Class<? extends Throwable> expected) {
        while (t != null) {
            if (expected.isInstance(t)) {
                return true;
            }
            t = t.getCause();
        }
        return false;
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
