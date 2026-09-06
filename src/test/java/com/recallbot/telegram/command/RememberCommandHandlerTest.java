package com.recallbot.telegram.command;

import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipService;
import com.recallbot.core.group.GroupService;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserService;
import com.recallbot.memory.MemoryEntity;
import com.recallbot.memory.MemoryService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RememberCommandHandlerTest {

    @Mock
    private GroupService groupService;
    @Mock
    private UserService userService;
    @Mock
    private GroupMembershipService groupMembershipService;
    @Mock
    private MemoryService memoryService;
    @Mock
    private TelegramClient telegramClient;

    private RememberCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RememberCommandHandler(
                groupService,
                userService,
                groupMembershipService,
                memoryService,
                telegramClient
        );
    }

    @Test
    @DisplayName("canHandle responds to /remember")
    void canHandleMatches() {
        assertThat(handler.canHandle("/remember")).isTrue();
        assertThat(handler.canHandle("/REMEMBER")).isTrue();
        assertThat(handler.canHandle("/ask")).isFalse();
    }

    @Test
    @DisplayName("handle rejects private chat with explanation")
    void handleRejectsPrivateChat() {
        ChatDto privateChat = new ChatDto(12345L, "private", null, null);
        MessageDto message = new MessageDto(501L, new UserDto(12345L, false, "Alice", null, null), privateChat, 1000L, "/remember Fact", null, null);

        handler.handle(message);

        verify(telegramClient).sendMessage(
                eq(12345L),
                contains("can only be used within a group chat"),
                isNull(),
                eq(501L)
        );
        verify(memoryService, never()).recordExplicitMemory(any(), any(), any(), any());
    }

    @Test
    @DisplayName("handle sends usage example when statement is empty")
    void handleEmptyStatementShowsUsage() {
        ChatDto groupChat = new ChatDto(-1001234567890L, "supergroup", "Team", null);
        UserDto userDto = new UserDto(111L, false, "Alice", null, "alice_dev");
        MessageDto message = new MessageDto(501L, userDto, groupChat, 1000L, "/remember   ", null, null);

        GroupEntity group = new GroupEntity(-1001234567890L, "Team");
        UserEntity user = new UserEntity(111L, "alice_dev", "Alice", null);
        when(groupService.resolveGroup(groupChat)).thenReturn(group);
        when(userService.resolveUser(userDto)).thenReturn(user);

        handler.handle(message);

        verify(telegramClient).sendMessage(
                eq(-1001234567890L),
                contains("Please specify what to remember"),
                isNull(),
                eq(501L)
        );
        verify(memoryService, never()).recordExplicitMemory(any(), any(), any(), any());
    }

    @Test
    @DisplayName("handle successfully records explicit memory and replies with confirmation")
    void handleValidStatementSuccess() {
        ChatDto groupChat = new ChatDto(-1001234567890L, "supergroup", "Team", null);
        UserDto userDto = new UserDto(111L, false, "Alice", null, "alice_dev");
        MessageDto message = new MessageDto(501L, userDto, groupChat, 1000L, "/remember The staging server IP is 192.168.1.50", null, null);

        GroupEntity group = new GroupEntity(-1001234567890L, "Team");
        org.springframework.test.util.ReflectionTestUtils.setField(group, "id", 1L);
        UserEntity user = new UserEntity(111L, "alice_dev", "Alice", null);
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 10L);

        when(groupService.resolveGroup(groupChat)).thenReturn(group);
        when(userService.resolveUser(userDto)).thenReturn(user);

        MemoryEntity memory = new MemoryEntity();
        memory.setContent("The staging server IP is 192.168.1.50");
        when(memoryService.recordExplicitMemory(1L, 10L, 501L, "The staging server IP is 192.168.1.50"))
                .thenReturn(memory);
        when(telegramClient.sendMessage(eq(-1001234567890L), anyString(), eq("MarkdownV2"), eq(501L))).thenReturn(true);

        handler.handle(message);

        verify(memoryService).recordExplicitMemory(1L, 10L, 501L, "The staging server IP is 192.168.1.50");
        verify(telegramClient).sendMessage(
                eq(-1001234567890L),
                contains("Recorded in group memory"),
                eq("MarkdownV2"),
                eq(501L)
        );
    }
}
