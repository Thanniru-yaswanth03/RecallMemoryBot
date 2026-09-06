package com.recallbot.core.message;

import com.recallbot.config.properties.RecallProperties;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipService;
import com.recallbot.core.group.GroupRole;
import com.recallbot.core.group.GroupService;
import com.recallbot.core.message.event.MessagePersistedEvent;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserService;
import com.recallbot.telegram.TelegramUpdateDeduplicator;
import com.recallbot.telegram.dto.ChatDto;
import com.recallbot.telegram.dto.MessageDto;
import com.recallbot.telegram.dto.UpdateDto;
import com.recallbot.telegram.dto.UserDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Core engine responsible for ingesting Telegram updates, resolving user and group identities,
 * establishing memberships, and persisting messages into the primary database.
 */
@Service
public class MessageIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MessageIngestionService.class);

    private final UserService userService;
    private final GroupService groupService;
    private final GroupMembershipService groupMembershipService;
    private final MessageRepository messageRepository;
    private final TelegramUpdateDeduplicator deduplicator;
    private final ApplicationEventPublisher eventPublisher;
    private final Long configuredBotUserId;

    public MessageIngestionService(
            UserService userService,
            GroupService groupService,
            GroupMembershipService groupMembershipService,
            MessageRepository messageRepository,
            TelegramUpdateDeduplicator deduplicator,
            ApplicationEventPublisher eventPublisher,
            RecallProperties properties
    ) {
        this.userService = userService;
        this.groupService = groupService;
        this.groupMembershipService = groupMembershipService;
        this.messageRepository = messageRepository;
        this.deduplicator = deduplicator;
        this.eventPublisher = eventPublisher;
        this.configuredBotUserId = properties.telegram() != null ? properties.telegram().botUserId() : null;
    }

    /**
     * Ingests an incoming Telegram UpdateDto.
     * Evaluates message type, ignores unsupported/non-group messages, resolves identities,
     * persists/updates domain messages, and publishes MessagePersistedEvent.
     *
     * @param update the incoming update
     */
    @Transactional
    public void ingestUpdate(UpdateDto update) {
        if (update == null) {
            log.warn("Cannot ingest null UpdateDto");
            return;
        }

        MessageDto message = update.effectiveMessage();
        if (message == null) {
            log.debug("Skipping update without effective message: update_id={}", update.updateId());
            return;
        }

        ChatDto chat = message.chat();
        if (chat == null || !chat.isGroupOrSupergroup()) {
            log.debug("Skipping message from non-group chat: chat_id={}", chat != null ? chat.id() : null);
            return;
        }

        UserDto from = message.from();
        if (from == null) {
            log.debug("Skipping message without sender: message_id={}", message.messageId());
            return;
        }

        // Filter out messages from bots, including bot self-messages
        if (Boolean.TRUE.equals(from.isBot()) || (configuredBotUserId != null && configuredBotUserId.equals(from.id()))) {
            log.debug("Skipping message from bot: user_id={}", from.id());
            return;
        }

        // Extract content (text or media caption)
        String content = message.extractContent();
        if (content == null || content.isBlank()) {
            log.debug("Skipping non-text or empty message: message_id={}", message.messageId());
            return;
        }

        // Skip bot commands (e.g. /ask, /remember, /forget) from conversational history
        if (content.stripLeading().startsWith("/")) {
            log.debug("Skipping command message from conversational history: message_id={}", message.messageId());
            return;
        }

        // Resolve user, group, and membership
        UserEntity user = userService.resolveUser(from);
        GroupEntity group = groupService.resolveGroup(chat);
        groupMembershipService.ensureMembership(group, user, GroupRole.MEMBER);

        // Associate group_id with telegram_updates record
        if (update.updateId() != null) {
            deduplicator.associateGroupId(update.updateId(), group.getId());
        }

        boolean isEdit = (update.editedMessage() != null);
        if (isEdit) {
            handleEditedMessage(group, user, message, content);
        } else {
            handleNewMessage(group, user, message, content);
        }
    }

    private void handleNewMessage(GroupEntity group, UserEntity user, MessageDto message, String content) {
        Long telegramMessageId = message.messageId();
        Optional<MessageEntity> existing = messageRepository.findByGroupIdAndTelegramMessageId(group.getId(), telegramMessageId);

        if (existing.isPresent()) {
            log.debug("Message already persisted for group_id={}, telegram_message_id={}; skipping",
                    group.getId(), telegramMessageId);
            return;
        }

        Instant sentAt = message.date() != null ? Instant.ofEpochSecond(message.date()) : Instant.now();
        MessageEntity messageEntity = new MessageEntity(group, user, telegramMessageId, content, sentAt);

        if (message.replyToMessage() != null && message.replyToMessage().messageId() != null) {
            messageEntity.setReplyToTelegramMessageId(message.replyToMessage().messageId());
        }

        try {
            MessageEntity saved = messageRepository.save(messageEntity);
            log.debug("Persisted message id={} for group_id={}, telegram_message_id={}",
                    saved.getId(), group.getId(), telegramMessageId);
            eventPublisher.publishEvent(new MessagePersistedEvent(saved.getId(), group.getId(), false));
        } catch (DataIntegrityViolationException e) {
            log.debug("Concurrent duplicate message insertion prevented: group_id={}, telegram_message_id={}",
                    group.getId(), telegramMessageId);
        }
    }

    private void handleEditedMessage(GroupEntity group, UserEntity user, MessageDto message, String content) {
        Long telegramMessageId = message.messageId();
        Optional<MessageEntity> existingOpt = messageRepository.findByGroupIdAndTelegramMessageId(group.getId(), telegramMessageId);

        Instant editedAt = message.date() != null ? Instant.ofEpochSecond(message.date()) : Instant.now();

        if (existingOpt.isPresent()) {
            MessageEntity existing = existingOpt.get();
            existing.setContent(content);
            existing.setEditedAt(editedAt);
            MessageEntity saved = messageRepository.save(existing);
            log.debug("Updated edited message id={} for group_id={}, telegram_message_id={}",
                    saved.getId(), group.getId(), telegramMessageId);
            eventPublisher.publishEvent(new MessagePersistedEvent(saved.getId(), group.getId(), true));
        } else {
            // If the original message wasn't previously captured, persist it now with edited_at timestamp
            Instant sentAt = editedAt;
            MessageEntity messageEntity = new MessageEntity(group, user, telegramMessageId, content, sentAt);
            messageEntity.setEditedAt(editedAt);
            if (message.replyToMessage() != null && message.replyToMessage().messageId() != null) {
                messageEntity.setReplyToTelegramMessageId(message.replyToMessage().messageId());
            }
            try {
                MessageEntity saved = messageRepository.save(messageEntity);
                log.debug("Persisted previously unseen edited message id={} for group_id={}", saved.getId(), group.getId());
                eventPublisher.publishEvent(new MessagePersistedEvent(saved.getId(), group.getId(), true));
            } catch (DataIntegrityViolationException e) {
                log.debug("Concurrent insert for edited message prevented: group_id={}, telegram_message_id={}",
                        group.getId(), telegramMessageId);
            }
        }
    }
}
