package com.recallbot.privacy;

import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipService;
import com.recallbot.core.group.GroupRepository;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserService;
import com.recallbot.memory.MemoryEntity;
import com.recallbot.memory.MemoryService;
import com.recallbot.memory.MemorySourceEntity;
import com.recallbot.memory.MemorySourceRepository;
import com.recallbot.telegram.TelegramClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivacyServiceTest {

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private GroupRepository groupRepository;
    @Mock
    private UserService userService;
    @Mock
    private GroupMembershipService groupMembershipService;
    @Mock
    private MemoryService memoryService;
    @Mock
    private MemorySourceRepository memorySourceRepository;
    @Mock
    private TelegramClient telegramClient;

    private PrivacyService privacyService;

    @BeforeEach
    void setUp() {
        privacyService = new PrivacyService(
                messageRepository,
                groupRepository,
                userService,
                groupMembershipService,
                memoryService,
                memorySourceRepository,
                telegramClient
        );
    }

    @Test
    @DisplayName("forgetSingleMessage allows original author to delete their message")
    void forgetSingleMessageByAuthorSuccess() {
        UserEntity author = new UserEntity(111L, "alice", "Alice", null);
        MessageEntity message = new MessageEntity();
        message.setUser(author);

        when(messageRepository.findByGroupIdAndTelegramMessageId(1L, 501L))
                .thenReturn(Optional.of(message));

        boolean result = privacyService.forgetSingleMessage(1L, 111L, 501L, -1001234567890L);

        assertThat(result).isTrue();
        verify(messageRepository).delete(message);
        verify(memoryService).cleanupOrphanedMemories(1L);
    }

    @Test
    @DisplayName("forgetSingleMessage allows group administrator to delete another user's message")
    void forgetSingleMessageByAdminSuccess() {
        UserEntity author = new UserEntity(111L, "alice", "Alice", null);
        MessageEntity message = new MessageEntity();
        message.setUser(author);

        when(messageRepository.findByGroupIdAndTelegramMessageId(1L, 501L))
                .thenReturn(Optional.of(message));

        // Caller 999 is admin via TelegramClient
        when(telegramClient.isChatAdmin(-1001234567890L, 999L)).thenReturn(true);

        boolean result = privacyService.forgetSingleMessage(1L, 999L, 501L, -1001234567890L);

        assertThat(result).isTrue();
        verify(messageRepository).delete(message);
        verify(memoryService).cleanupOrphanedMemories(1L);
    }

    @Test
    @DisplayName("forgetSingleMessage rejects non-author non-admin with SecurityException")
    void forgetSingleMessageUnauthorizedThrows() {
        UserEntity author = new UserEntity(111L, "alice", "Alice", null);
        MessageEntity message = new MessageEntity();
        message.setUser(author);

        when(messageRepository.findByGroupIdAndTelegramMessageId(1L, 501L))
                .thenReturn(Optional.of(message));

        // Caller 888 is not admin
        when(telegramClient.isChatAdmin(-1001234567890L, 888L)).thenReturn(false);
        when(userService.findByTelegramUserId(888L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> privacyService.forgetSingleMessage(1L, 888L, 501L, -1001234567890L))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Permission denied");

        verify(messageRepository, never()).delete(any());
    }

    @Test
    @DisplayName("forgetUser hard-deletes unreferenced chatter and anonymizes provenance messages to [Former Member]")
    void forgetUserSelectiveAnonymization() {
        UserEntity user = new UserEntity(111L, "alice", "Alice", null);
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 10L);

        when(userService.findByTelegramUserId(111L)).thenReturn(Optional.of(user));

        UserEntity anonymizedUser = new UserEntity(0L, null, "[Former Member]", null);
        when(userService.getOrCreateAnonymizedUser()).thenReturn(anonymizedUser);

        // Message 1 supports a decision memory
        MessageEntity msg1 = new MessageEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(msg1, "id", 101L);
        msg1.setUser(user);
        when(memorySourceRepository.findByMessageId(101L)).thenReturn(List.of(new MemorySourceEntity(new MemoryEntity(), msg1)));

        // Message 2 is unreferenced personal chatter
        MessageEntity msg2 = new MessageEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(msg2, "id", 102L);
        msg2.setUser(user);
        when(memorySourceRepository.findByMessageId(102L)).thenReturn(List.of());

        when(messageRepository.findByGroupIdAndUserId(1L, 10L)).thenReturn(List.of(msg1, msg2));

        PrivacyService.ForgetMeResult result = privacyService.forgetUser(1L, 111L);

        assertThat(result.deletedMessages()).isEqualTo(1);
        assertThat(result.anonymizedMessages()).isEqualTo(1);

        // Msg1 was anonymized to [Former Member]
        assertThat(msg1.getUser()).isEqualTo(anonymizedUser);
        verify(messageRepository).save(msg1);

        // Msg2 was deleted
        verify(messageRepository).delete(msg2);

        // Membership removed and orphans cleaned up
        verify(groupMembershipService).removeMembership(1L, 10L);
        verify(memoryService).cleanupOrphanedMemories(1L);
    }

    @Test
    @DisplayName("forgetAll rejects non-admin with SecurityException")
    void forgetAllNonAdminRejected() {
        when(telegramClient.isChatAdmin(-1001234567890L, 777L)).thenReturn(false);
        when(userService.findByTelegramUserId(777L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> privacyService.forgetAll(1L, 777L, -1001234567890L))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Permission denied: Only group administrators");

        verify(groupRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("forgetAll allows group admin to delete group and trigger database cascade")
    void forgetAllAdminSuccess() {
        when(telegramClient.isChatAdmin(-1001234567890L, 999L)).thenReturn(true);
        when(groupRepository.existsById(1L)).thenReturn(true);

        boolean result = privacyService.forgetAll(1L, 999L, -1001234567890L);

        assertThat(result).isTrue();
        verify(groupRepository).deleteById(1L);
    }
}
