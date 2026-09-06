package com.recallbot.privacy;

import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipService;
import com.recallbot.core.group.GroupRepository;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserService;
import com.recallbot.memory.MemoryService;
import com.recallbot.memory.MemorySourceEntity;
import com.recallbot.memory.MemorySourceRepository;
import com.recallbot.telegram.TelegramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing privacy erasures, member anonymization, and administrative group purges.
 * Strictly guarantees that shared group knowledge is preserved during individual member departures.
 */
@Service
public class PrivacyService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);

    private final MessageRepository messageRepository;
    private final GroupRepository groupRepository;
    private final UserService userService;
    private final GroupMembershipService groupMembershipService;
    private final MemoryService memoryService;
    private final MemorySourceRepository memorySourceRepository;
    private final TelegramClient telegramClient;

    public PrivacyService(
            MessageRepository messageRepository,
            GroupRepository groupRepository,
            UserService userService,
            GroupMembershipService groupMembershipService,
            MemoryService memoryService,
            MemorySourceRepository memorySourceRepository,
            TelegramClient telegramClient
    ) {
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository must not be null");
        this.groupRepository = Objects.requireNonNull(groupRepository, "groupRepository must not be null");
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
        this.groupMembershipService = Objects.requireNonNull(groupMembershipService, "groupMembershipService must not be null");
        this.memoryService = Objects.requireNonNull(memoryService, "memoryService must not be null");
        this.memorySourceRepository = Objects.requireNonNull(memorySourceRepository, "memorySourceRepository must not be null");
        this.telegramClient = Objects.requireNonNull(telegramClient, "telegramClient must not be null");
    }

    public record ForgetMeResult(int deletedMessages, int anonymizedMessages) {}

    /**
     * Deletes a single message from group memory if caller is author or group admin.
     * Cascades deletion to embeddings and memory sources, and cleans up orphaned memories.
     *
     * @param groupId           the group database ID
     * @param telegramUserId    the requesting Telegram user ID
     * @param telegramMessageId the target Telegram message ID to delete
     * @param telegramChatId    the Telegram chat ID (for admin verification)
     * @return true if deleted, false if message not found
     * @throws SecurityException if caller is neither message author nor group administrator
     */
    @Transactional
    public boolean forgetSingleMessage(
            Long groupId,
            Long telegramUserId,
            Long telegramMessageId,
            Long telegramChatId
    ) {
        if (groupId == null || telegramUserId == null || telegramMessageId == null) {
            throw new IllegalArgumentException("groupId, telegramUserId, and telegramMessageId must not be null");
        }

        Optional<MessageEntity> messageOpt = messageRepository.findByGroupIdAndTelegramMessageId(groupId, telegramMessageId);
        if (messageOpt.isEmpty()) {
            log.debug("Message not found for deletion: group_id={}, telegram_message_id={}",
                    groupId, telegramMessageId);
            return false;
        }

        MessageEntity message = messageOpt.get();
        boolean isAuthor = message.getUser() != null
                && Objects.equals(message.getUser().getTelegramUserId(), telegramUserId);

        boolean isAdmin = isAuthor || isCallerAdmin(groupId, telegramUserId, telegramChatId);

        if (!isAuthor && !isAdmin) {
            log.warn("Unauthorized attempt to delete message_id={} by user_id={}",
                    telegramMessageId, telegramUserId);
            throw new SecurityException("Permission denied: You can only delete your own messages unless you are an administrator.");
        }

        if (message.getId() != null) {
            List<MemorySourceEntity> sources = memorySourceRepository.findByMessageId(message.getId());
            if (!sources.isEmpty()) {
                memorySourceRepository.deleteAll(sources);
                memorySourceRepository.flush();
            }
        }

        messageRepository.delete(message);
        messageRepository.flush();
        memoryService.cleanupOrphanedMemories(groupId);

        log.info("[AUDIT] Action=FORGET_MESSAGE groupId={} telegramMessageId={} actorId={}",
                groupId, telegramMessageId, telegramUserId);
        return true;
    }

    /**
     * Executes privacy erasure for a single user in a group:
     * - Hard-deletes unreferenced personal chatter.
     * - Anonymizes provenance messages supporting shared group memories to [Former Member].
     * - Removes group membership row.
     * - Purges any derived memories whose sources are completely gone.
     *
     * @param groupId        the group database ID
     * @param telegramUserId the requesting Telegram user ID
     * @return ForgetMeResult detailing deleted vs anonymized messages
     */
    @Transactional
    public ForgetMeResult forgetUser(Long groupId, Long telegramUserId) {
        if (groupId == null || telegramUserId == null) {
            throw new IllegalArgumentException("groupId and telegramUserId must not be null");
        }

        Optional<UserEntity> userOpt = userService.findByTelegramUserId(telegramUserId);
        if (userOpt.isEmpty()) {
            return new ForgetMeResult(0, 0);
        }

        UserEntity user = userOpt.get();
        List<MessageEntity> userMessages = messageRepository.findByGroupIdAndUserId(groupId, user.getId());

        UserEntity anonymizedUser = userService.getOrCreateAnonymizedUser();

        int deletedCount = 0;
        int anonymizedCount = 0;

        for (MessageEntity message : userMessages) {
            List<MemorySourceEntity> sources = memorySourceRepository.findByMessageId(message.getId());
            if (!sources.isEmpty()) {
                // Preserved as provenance for shared group knowledge, but author is scrubbed
                message.setUser(anonymizedUser);
                messageRepository.save(message);
                anonymizedCount++;
            } else {
                // Personal unreferenced chatter is permanently destroyed
                messageRepository.delete(message);
                deletedCount++;
            }
        }

        // Remove membership
        groupMembershipService.removeMembership(groupId, user.getId());
        messageRepository.flush();

        // Cleanup any derived memories that lost all sources
        memoryService.cleanupOrphanedMemories(groupId);

        log.info("[AUDIT] Action=FORGET_ME groupId={} actorId={} deletedMessages={} anonymizedMessages={}",
                groupId, telegramUserId, deletedCount, anonymizedCount);

        return new ForgetMeResult(deletedCount, anonymizedCount);
    }

    /**
     * Permanently wipes all data for a group, atomically cascading across all 8 tables.
     * Strictly verifies caller is a group administrator.
     *
     * @param groupId        the group database ID
     * @param telegramUserId the requesting Telegram user ID
     * @param telegramChatId the Telegram chat ID
     * @return true if group was deleted
     * @throws SecurityException if caller is not an administrator
     */
    @Transactional
    public boolean forgetAll(Long groupId, Long telegramUserId, Long telegramChatId) {
        if (groupId == null || telegramUserId == null) {
            throw new IllegalArgumentException("groupId and telegramUserId must not be null");
        }

        if (!isCallerAdmin(groupId, telegramUserId, telegramChatId)) {
            log.warn("Unauthorized /forget all attempt for group_id={} by user_id={}",
                    groupId, telegramUserId);
            throw new SecurityException("Permission denied: Only group administrators can erase all group memory.");
        }

        if (groupRepository.existsById(groupId)) {
            groupRepository.deleteById(groupId);
            log.info("[AUDIT] Action=FORGET_ALL groupId={} actorId={}", groupId, telegramUserId);
            return true;
        }
        return false;
    }

    private boolean isCallerAdmin(Long groupId, Long telegramUserId, Long telegramChatId) {
        if (telegramChatId != null && telegramClient.isChatAdmin(telegramChatId, telegramUserId)) {
            return true;
        }

        return userService.findByTelegramUserId(telegramUserId)
                .map(user -> groupMembershipService.isGroupAdmin(groupId, user.getId()))
                .orElse(false);
    }
}
