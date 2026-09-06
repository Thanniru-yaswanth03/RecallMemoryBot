package com.recallbot.telegram.command;

import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipService;
import com.recallbot.core.group.GroupService;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserService;
import com.recallbot.privacy.PrivacyService;
import com.recallbot.telegram.TelegramClient;
import com.recallbot.telegram.dto.ChatDto;
import com.recallbot.telegram.dto.MessageDto;
import com.recallbot.telegram.dto.UserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForgetCommandHandlerTest {

    @Mock
    private GroupService groupService;
    @Mock
    private UserService userService;
    @Mock
    private GroupMembershipService groupMembershipService;
    @Mock
    private PrivacyService privacyService;
    @Mock
    private TelegramClient telegramClient;

    private ForgetCommandHandler handler;

    private ChatDto groupChat;
    private UserDto userDto;
    private GroupEntity group;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        handler = new ForgetCommandHandler(
                groupService,
                userService,
                groupMembershipService,
                privacyService,
                telegramClient
        );

        groupChat = new ChatDto(-1001234567890L, "supergroup", "Team", null);
        userDto = new UserDto(111L, false, "Alice", null, "alice_dev");
        group = new GroupEntity(-1001234567890L, "Team");
        org.springframework.test.util.ReflectionTestUtils.setField(group, "id", 1L);
        user = new UserEntity(111L, "alice_dev", "Alice", null);
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 10L);
    }

    @Test
    @DisplayName("canHandle responds to /forget")
    void canHandleMatches() {
        assertThat(handler.canHandle("/forget")).isTrue();
        assertThat(handler.canHandle("/FORGET")).isTrue();
        assertThat(handler.canHandle("/ask")).isFalse();
    }

    @Test
    @DisplayName("handle empty args displays usage options")
    void handleEmptyArgsShowsUsage() {
        MessageDto message = new MessageDto(501L, userDto, groupChat, 1000L, "/forget", null, null);
        when(groupService.resolveGroup(groupChat)).thenReturn(group);
        when(userService.resolveUser(userDto)).thenReturn(user);

        handler.handle(message);

        verify(telegramClient).sendMessage(
                eq(-1001234567890L),
                contains("Usage: /forget me"),
                isNull(),
                eq(501L)
        );
    }

    @Test
    @DisplayName("handle /forget me calls privacyService and confirms anonymization")
    void handleForgetMeSuccess() {
        MessageDto message = new MessageDto(501L, userDto, groupChat, 1000L, "/forget me", null, null);
        when(groupService.resolveGroup(groupChat)).thenReturn(group);
        when(userService.resolveUser(userDto)).thenReturn(user);
        when(privacyService.forgetUser(1L, 111L)).thenReturn(new PrivacyService.ForgetMeResult(2, 1));

        handler.handle(message);

        verify(privacyService).forgetUser(1L, 111L);
        verify(telegramClient).sendMessage(
                eq(-1001234567890L),
                contains("personal identity and non-shared messages"),
                isNull(),
                eq(501L)
        );
    }

    @Test
    @DisplayName("handle /forget message <id> calls privacyService and confirms deletion")
    void handleForgetMessageSuccess() {
        MessageDto message = new MessageDto(501L, userDto, groupChat, 1000L, "/forget message 777", null, null);
        when(groupService.resolveGroup(groupChat)).thenReturn(group);
        when(userService.resolveUser(userDto)).thenReturn(user);
        when(privacyService.forgetSingleMessage(1L, 111L, 777L, -1001234567890L)).thenReturn(true);

        handler.handle(message);

        verify(privacyService).forgetSingleMessage(1L, 111L, 777L, -1001234567890L);
        verify(telegramClient).sendMessage(
                eq(-1001234567890L),
                contains("Message #777 has been deleted"),
                isNull(),
                eq(501L)
        );
    }

    @Test
    @DisplayName("handle /forget all by admin purges group memory")
    void handleForgetAllAdminSuccess() {
        MessageDto message = new MessageDto(501L, userDto, groupChat, 1000L, "/forget all", null, null);
        when(groupService.resolveGroup(groupChat)).thenReturn(group);
        when(userService.resolveUser(userDto)).thenReturn(user);
        when(privacyService.forgetAll(1L, 111L, -1001234567890L)).thenReturn(true);

        handler.handle(message);

        verify(privacyService).forgetAll(1L, 111L, -1001234567890L);
        verify(telegramClient).sendMessage(
                eq(-1001234567890L),
                contains("permanently erased"),
                isNull(),
                eq(501L)
        );
    }

    @Test
    @DisplayName("handle /forget all by non-admin sends permission denied warning")
    void handleForgetAllNonAdminDenied() {
        MessageDto message = new MessageDto(501L, userDto, groupChat, 1000L, "/forget all", null, null);
        when(groupService.resolveGroup(groupChat)).thenReturn(group);
        when(userService.resolveUser(userDto)).thenReturn(user);
        when(privacyService.forgetAll(1L, 111L, -1001234567890L))
                .thenThrow(new SecurityException("Permission denied: Only group administrators can erase all group memory."));

        handler.handle(message);

        verify(telegramClient).sendMessage(
                eq(-1001234567890L),
                contains("⛔ Permission denied"),
                isNull(),
                eq(501L)
        );
    }
}
