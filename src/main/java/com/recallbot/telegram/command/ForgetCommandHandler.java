package com.recallbot.telegram.command;

import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipService;
import com.recallbot.core.group.GroupRole;
import com.recallbot.core.group.GroupService;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserService;
import com.recallbot.privacy.PrivacyService;
import com.recallbot.telegram.TelegramClient;
import com.recallbot.telegram.dto.ChatDto;
import com.recallbot.telegram.dto.MessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Handles the /forget command and its privacy subcommands:
 * - /forget me
 * - /forget message <id>
 * - /forget all
 */
@Component
public class ForgetCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ForgetCommandHandler.class);

    private final GroupService groupService;
    private final UserService userService;
    private final GroupMembershipService groupMembershipService;
    private final PrivacyService privacyService;
    private final TelegramClient telegramClient;

    public ForgetCommandHandler(
            GroupService groupService,
            UserService userService,
            GroupMembershipService groupMembershipService,
            PrivacyService privacyService,
            TelegramClient telegramClient
    ) {
        this.groupService = Objects.requireNonNull(groupService, "groupService must not be null");
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
        this.groupMembershipService = Objects.requireNonNull(groupMembershipService, "groupMembershipService must not be null");
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService must not be null");
        this.telegramClient = Objects.requireNonNull(telegramClient, "telegramClient must not be null");
    }

    @Override
    public boolean canHandle(String command) {
        return "/forget".equalsIgnoreCase(command);
    }

    @Override
    public void handle(MessageDto message) {
        if (message == null) {
            return;
        }

        ChatDto chat = message.chat();
        if (chat == null || chat.id() == null) {
            log.warn("Cannot handle /forget command without valid chat");
            return;
        }

        Long chatId = chat.id();
        Long messageId = message.messageId();

        if (!chat.isGroupOrSupergroup()) {
            telegramClient.sendMessage(chatId, "The /forget command can only be used within a group chat.", null, messageId);
            return;
        }

        if (message.from() == null || message.from().id() == null) {
            log.warn("Cannot handle /forget command without sender in chat_id={}", chatId);
            return;
        }

        GroupEntity group = groupService.resolveGroup(chat);
        UserEntity user = userService.resolveUser(message.from());
        groupMembershipService.ensureMembership(group, user, GroupRole.MEMBER);

        String args = extractArgs(message.extractContent());
        if (args.isBlank()) {
            telegramClient.sendMessage(
                    chatId,
                    "Usage: /forget me, /forget message <id>, or /forget all",
                    null,
                    messageId
            );
            return;
        }

        String[] parts = args.split("\\s+");
        String subcommand = parts[0].toLowerCase();

        switch (subcommand) {
            case "me" -> handleForgetMe(group, message.from().id(), chatId, messageId);
            case "message", "msg" -> {
                if (parts.length < 2) {
                    telegramClient.sendMessage(chatId, "Usage: /forget message <telegram_message_id>", null, messageId);
                    return;
                }
                try {
                    Long targetMessageId = Long.parseLong(parts[1]);
                    handleForgetMessage(group, message.from().id(), targetMessageId, chatId, messageId);
                } catch (NumberFormatException e) {
                    telegramClient.sendMessage(chatId, "Invalid message ID. Example: /forget message 501", null, messageId);
                }
            }
            case "all" -> handleForgetAll(group, message.from().id(), chatId, messageId);
            default -> telegramClient.sendMessage(
                    chatId,
                    "Usage: /forget me, /forget message <id>, or /forget all",
                    null,
                    messageId
            );
        }
    }

    private void handleForgetMe(GroupEntity group, Long telegramUserId, Long chatId, Long replyToMessageId) {
        try {
            privacyService.forgetUser(group.getId(), telegramUserId);
            telegramClient.sendMessage(
                    chatId,
                    "Your personal identity and non-shared messages have been removed from this group. " +
                            "Shared group decisions and collective memories have been anonymized.",
                    null,
                    replyToMessageId
            );
        } catch (Exception e) {
            log.error("Failed executing /forget me for group_id={}, user_id={}: {}",
                    group.getId(), telegramUserId, e.getMessage(), e);
            telegramClient.sendMessage(chatId, "Failed to process privacy request. Please try again.", null, replyToMessageId);
        }
    }

    private void handleForgetMessage(GroupEntity group, Long telegramUserId, Long targetTelegramMessageId, Long chatId, Long replyToMessageId) {
        try {
            boolean deleted = privacyService.forgetSingleMessage(group.getId(), telegramUserId, targetTelegramMessageId, chatId);
            if (deleted) {
                telegramClient.sendMessage(
                        chatId,
                        "Message #" + targetTelegramMessageId + " has been deleted from group memory.",
                        null,
                        replyToMessageId
                );
            } else {
                telegramClient.sendMessage(
                        chatId,
                        "Message #" + targetTelegramMessageId + " not found in group memory.",
                        null,
                        replyToMessageId
                );
            }
        } catch (SecurityException e) {
            telegramClient.sendMessage(chatId, "⛔ " + e.getMessage(), null, replyToMessageId);
        } catch (Exception e) {
            log.error("Failed executing /forget message for group_id={}, msg_id={}: {}",
                    group.getId(), targetTelegramMessageId, e.getMessage(), e);
            telegramClient.sendMessage(chatId, "Failed to delete message. Please try again.", null, replyToMessageId);
        }
    }

    private void handleForgetAll(GroupEntity group, Long telegramUserId, Long chatId, Long replyToMessageId) {
        try {
            boolean deleted = privacyService.forgetAll(group.getId(), telegramUserId, chatId);
            if (deleted) {
                telegramClient.sendMessage(
                        chatId,
                        "All historical messages, embeddings, and memories for this group have been permanently erased.",
                        null,
                        replyToMessageId
                );
            } else {
                telegramClient.sendMessage(chatId, "Group memory was already empty.", null, replyToMessageId);
            }
        } catch (SecurityException e) {
            telegramClient.sendMessage(chatId, "⛔ " + e.getMessage(), null, replyToMessageId);
        } catch (Exception e) {
            log.error("Failed executing /forget all for group_id={}: {}", group.getId(), e.getMessage(), e);
            telegramClient.sendMessage(chatId, "Failed to erase group memory. Please try again.", null, replyToMessageId);
        }
    }

    private String extractArgs(String content) {
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
