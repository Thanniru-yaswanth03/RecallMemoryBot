package com.recallbot.telegram.command;

import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipService;
import com.recallbot.core.group.GroupRole;
import com.recallbot.core.group.GroupService;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserService;
import com.recallbot.memory.MemoryEntity;
import com.recallbot.memory.MemoryService;
import com.recallbot.telegram.TelegramClient;
import com.recallbot.telegram.dto.ChatDto;
import com.recallbot.telegram.dto.MessageDto;
import com.recallbot.telegram.util.TelegramMarkdownFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Handles the /remember command, recording explicit facts and decisions into group memory.
 */
@Component
public class RememberCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(RememberCommandHandler.class);

    private final GroupService groupService;
    private final UserService userService;
    private final GroupMembershipService groupMembershipService;
    private final MemoryService memoryService;
    private final TelegramClient telegramClient;

    public RememberCommandHandler(
            GroupService groupService,
            UserService userService,
            GroupMembershipService groupMembershipService,
            MemoryService memoryService,
            TelegramClient telegramClient
    ) {
        this.groupService = Objects.requireNonNull(groupService, "groupService must not be null");
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
        this.groupMembershipService = Objects.requireNonNull(groupMembershipService, "groupMembershipService must not be null");
        this.memoryService = Objects.requireNonNull(memoryService, "memoryService must not be null");
        this.telegramClient = Objects.requireNonNull(telegramClient, "telegramClient must not be null");
    }

    @Override
    public boolean canHandle(String command) {
        return "/remember".equalsIgnoreCase(command);
    }

    @Override
    public void handle(MessageDto message) {
        if (message == null) {
            return;
        }

        ChatDto chat = message.chat();
        if (chat == null || chat.id() == null) {
            log.warn("Cannot handle /remember command without valid chat");
            return;
        }

        Long chatId = chat.id();
        Long messageId = message.messageId();

        if (!chat.isGroupOrSupergroup()) {
            telegramClient.sendMessage(chatId, "The /remember command can only be used within a group chat.", null, messageId);
            return;
        }

        if (message.from() == null || message.from().id() == null) {
            log.warn("Cannot handle /remember command without sender in chat_id={}", chatId);
            return;
        }

        GroupEntity group = groupService.resolveGroup(chat);
        UserEntity user = userService.resolveUser(message.from());
        groupMembershipService.ensureMembership(group, user, GroupRole.MEMBER);

        String statement = extractStatement(message.extractContent());
        if (statement.isBlank()) {
            telegramClient.sendMessage(
                    chatId,
                    "Please specify what to remember. Example: /remember Meeting is Tuesdays at 10 AM.",
                    null,
                    messageId
            );
            return;
        }

        try {
            MemoryEntity memory = memoryService.recordExplicitMemory(group.getId(), user.getId(), messageId, statement);
            String author = (user.getUsername() != null && !user.getUsername().isBlank())
                    ? "@" + user.getUsername()
                    : user.getFirstName();

            String replyText = String.format(
                    "Recorded in group memory:\n*\"%s\"*\n\n_Logged by %s._",
                    TelegramMarkdownFormatter.escape(memory.getContent()),
                    TelegramMarkdownFormatter.escape(author)
            );

            boolean sent = telegramClient.sendMessage(chatId, replyText, "MarkdownV2", messageId);
            if (!sent) {
                // Fallback to plain text if MarkdownV2 formatting is rejected
                String plainReply = String.format(
                        "Recorded in group memory:\n\"%s\"\n\nLogged by %s.",
                        memory.getContent(), author
                );
                telegramClient.sendMessage(chatId, plainReply, null, messageId);
            }
        } catch (Exception e) {
            log.error("Failed to record explicit memory for group_id={}: {}", group.getId(), e.getMessage(), e);
            telegramClient.sendMessage(
                    chatId,
                    "Failed to record memory. Please try again shortly.",
                    null,
                    messageId
            );
        }
    }

    private String extractStatement(String content) {
        if (content == null) {
            return "";
        }
        String stripped = content.stripLeading();
        int spaceIdx = stripped.indexOf(' ');
        if (spaceIdx == -1) {
            return "";
        }
        return stripped.substring(spaceIdx + 1).trim();
    }
}
