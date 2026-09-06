package com.recallbot.core.message;

import com.recallbot.config.properties.RecallProperties;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipEntity;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageIngestionServiceTest {

    @Mock
    private UserService userService;

    @Mock
    private GroupService groupService;

    @Mock
    private GroupMembershipService groupMembershipService;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private TelegramUpdateDeduplicator deduplicator;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private static final Long BOT_USER_ID = 999999999L;
    private MessageIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        RecallProperties properties = new RecallProperties(
                new RecallProperties.Telegram("webhook", "recall_bot", "token", "secret", BOT_USER_ID),
                null, null, null
        );
        ingestionService = new MessageIngestionService(
                userService,
                groupService,
                groupMembershipService,
                messageRepository,
                deduplicator,
                eventPublisher,
                properties
        );
    }

    @Test
    @DisplayName("Normal group text message is resolved, persisted, and event published")
    void normalGroupTextMessagePersisted() {
        UserDto from = new UserDto(100L, false, "Alice", "Dev", "alice_dev");
        ChatDto chat = new ChatDto(-1001L, "supergroup", "Dev Group", "dev_group");
        MessageDto message = new MessageDto(501L, from, chat, 1690000000L, "Hello world", null, null);
        UpdateDto update = new UpdateDto(1L, message, null, null);

        UserEntity userEntity = new UserEntity(100L, "alice_dev", "Alice", "Dev", false);
        userEntity.setId(10L);
        GroupEntity groupEntity = new GroupEntity(-1001L, "Dev Group");
        groupEntity.setId(20L);

        when(userService.resolveUser(from)).thenReturn(userEntity);
        when(groupService.resolveGroup(chat)).thenReturn(groupEntity);
        when(groupMembershipService.ensureMembership(groupEntity, userEntity, GroupRole.MEMBER))
                .thenReturn(new GroupMembershipEntity(20L, 10L, GroupRole.MEMBER));
        when(messageRepository.findByGroupIdAndTelegramMessageId(20L, 501L)).thenReturn(Optional.empty());

        MessageEntity savedEntity = new MessageEntity(groupEntity, userEntity, 501L, "Hello world", Instant.ofEpochSecond(1690000000L));
        savedEntity.setId(30L);
        when(messageRepository.save(any(MessageEntity.class))).thenReturn(savedEntity);

        ingestionService.ingestUpdate(update);

        verify(userService).resolveUser(from);
        verify(groupService).resolveGroup(chat);
        verify(groupMembershipService).ensureMembership(groupEntity, userEntity, GroupRole.MEMBER);
        verify(deduplicator).associateGroupId(1L, 20L);

        ArgumentCaptor<MessageEntity> msgCaptor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository).save(msgCaptor.capture());
        MessageEntity toSave = msgCaptor.getValue();
        assertThat(toSave.getContent()).isEqualTo("Hello world");
        assertThat(toSave.getTelegramMessageId()).isEqualTo(501L);
        assertThat(toSave.getGroup()).isEqualTo(groupEntity);
        assertThat(toSave.getUser()).isEqualTo(userEntity);

        ArgumentCaptor<MessagePersistedEvent> eventCaptor = ArgumentCaptor.forClass(MessagePersistedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        MessagePersistedEvent event = eventCaptor.getValue();
        assertThat(event.messageId()).isEqualTo(30L);
        assertThat(event.groupId()).isEqualTo(20L);
        assertThat(event.isEdit()).isFalse();
    }

    @Test
    @DisplayName("Photo with text caption is persisted using the caption")
    void photoWithCaptionPersisted() {
        UserDto from = new UserDto(101L, false, "Bob", null, "bob_dev");
        ChatDto chat = new ChatDto(-1002L, "group", "Architecture Chat", null);
        MessageDto message = new MessageDto(502L, from, chat, 1690000001L, null, "Diagram of our database schema", null);
        UpdateDto update = new UpdateDto(2L, message, null, null);

        UserEntity userEntity = new UserEntity(101L, "bob_dev", "Bob", null, false);
        userEntity.setId(11L);
        GroupEntity groupEntity = new GroupEntity(-1002L, "Architecture Chat");
        groupEntity.setId(21L);

        when(userService.resolveUser(from)).thenReturn(userEntity);
        when(groupService.resolveGroup(chat)).thenReturn(groupEntity);
        when(messageRepository.findByGroupIdAndTelegramMessageId(21L, 502L)).thenReturn(Optional.empty());

        MessageEntity savedEntity = new MessageEntity(groupEntity, userEntity, 502L, "Diagram of our database schema", Instant.now());
        savedEntity.setId(31L);
        when(messageRepository.save(any(MessageEntity.class))).thenReturn(savedEntity);

        ingestionService.ingestUpdate(update);

        ArgumentCaptor<MessageEntity> msgCaptor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository).save(msgCaptor.capture());
        assertThat(msgCaptor.getValue().getContent()).isEqualTo("Diagram of our database schema");
    }

    @Test
    @DisplayName("Sticker or media without text is discarded without persistence")
    void mediaWithoutTextDiscarded() {
        UserDto from = new UserDto(102L, false, "Charlie", null, "charlie");
        ChatDto chat = new ChatDto(-1003L, "supergroup", "Fun Chat", null);
        // text and caption are null
        MessageDto message = new MessageDto(503L, from, chat, 1690000002L, null, null, null);
        UpdateDto update = new UpdateDto(3L, message, null, null);

        ingestionService.ingestUpdate(update);

        verifyNoInteractions(userService);
        verifyNoInteractions(groupService);
        verifyNoInteractions(messageRepository);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("Bot messages are discarded silently")
    void botMessagesDiscarded() {
        // is_bot = true
        UserDto botUser = new UserDto(888L, true, "OtherBot", null, "other_bot");
        ChatDto chat = new ChatDto(-1004L, "supergroup", "Team Chat", null);
        MessageDto message = new MessageDto(504L, botUser, chat, 1690000003L, "Bot auto-reply", null, null);
        UpdateDto update = new UpdateDto(4L, message, null, null);

        ingestionService.ingestUpdate(update);

        verifyNoInteractions(userService);
        verifyNoInteractions(messageRepository);

        // Configured bot user ID match
        UserDto selfBot = new UserDto(BOT_USER_ID, false, "RecallBot", null, "recall_bot");
        MessageDto selfMessage = new MessageDto(505L, selfBot, chat, 1690000004L, "Self bot message", null, null);
        UpdateDto update2 = new UpdateDto(5L, selfMessage, null, null);

        ingestionService.ingestUpdate(update2);

        verifyNoInteractions(userService);
        verifyNoInteractions(messageRepository);
    }

    @Test
    @DisplayName("Private chats are ignored for group memory ingestion")
    void privateChatsIgnored() {
        UserDto from = new UserDto(103L, false, "Dave", null, "dave");
        ChatDto privateChat = new ChatDto(103L, "private", null, "dave");
        MessageDto message = new MessageDto(506L, from, privateChat, 1690000005L, "Private message to bot", null, null);
        UpdateDto update = new UpdateDto(6L, message, null, null);

        ingestionService.ingestUpdate(update);

        verifyNoInteractions(userService);
        verifyNoInteractions(groupService);
        verifyNoInteractions(messageRepository);
    }

    @Test
    @DisplayName("Slash commands are excluded from normal chat conversation storage")
    void commandsExcludedFromChatStorage() {
        UserDto from = new UserDto(104L, false, "Eve", null, "eve");
        ChatDto chat = new ChatDto(-1005L, "supergroup", "Main Chat", null);
        MessageDto message = new MessageDto(507L, from, chat, 1690000006L, "/ask What did we decide?", null, null);
        UpdateDto update = new UpdateDto(7L, message, null, null);

        ingestionService.ingestUpdate(update);

        verifyNoInteractions(userService);
        verifyNoInteractions(groupService);
        verifyNoInteractions(messageRepository);
    }

    @Test
    @DisplayName("Edited message updates existing content and publishes event with isEdit=true")
    void editedMessageUpdatesContent() {
        UserDto from = new UserDto(105L, false, "Frank", null, "frank");
        ChatDto chat = new ChatDto(-1006L, "supergroup", "Design Chat", null);
        MessageDto editedMessage = new MessageDto(508L, from, chat, 1690000010L, "Updated message content", null, null);
        UpdateDto update = new UpdateDto(8L, null, editedMessage, null);

        UserEntity userEntity = new UserEntity(105L, "frank", "Frank", null, false);
        userEntity.setId(15L);
        GroupEntity groupEntity = new GroupEntity(-1006L, "Design Chat");
        groupEntity.setId(25L);

        when(userService.resolveUser(from)).thenReturn(userEntity);
        when(groupService.resolveGroup(chat)).thenReturn(groupEntity);

        MessageEntity existing = new MessageEntity(groupEntity, userEntity, 508L, "Original message content", Instant.ofEpochSecond(1690000000L));
        existing.setId(35L);
        when(messageRepository.findByGroupIdAndTelegramMessageId(25L, 508L)).thenReturn(Optional.of(existing));
        when(messageRepository.save(existing)).thenReturn(existing);

        ingestionService.ingestUpdate(update);

        assertThat(existing.getContent()).isEqualTo("Updated message content");
        assertThat(existing.getEditedAt()).isEqualTo(Instant.ofEpochSecond(1690000010L));
        verify(messageRepository).save(existing);

        ArgumentCaptor<MessagePersistedEvent> eventCaptor = ArgumentCaptor.forClass(MessagePersistedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        MessagePersistedEvent event = eventCaptor.getValue();
        assertThat(event.messageId()).isEqualTo(35L);
        assertThat(event.groupId()).isEqualTo(25L);
        assertThat(event.isEdit()).isTrue();
    }

    @Test
    @DisplayName("Duplicate message delivery skips database insertion idempotently")
    void duplicateMessageDeliverySkipped() {
        UserDto from = new UserDto(106L, false, "Grace", null, "grace");
        ChatDto chat = new ChatDto(-1007L, "supergroup", "Test Chat", null);
        MessageDto message = new MessageDto(509L, from, chat, 1690000000L, "Already saved text", null, null);
        UpdateDto update = new UpdateDto(9L, message, null, null);

        UserEntity userEntity = new UserEntity(106L, "grace", "Grace", null, false);
        userEntity.setId(16L);
        GroupEntity groupEntity = new GroupEntity(-1007L, "Test Chat");
        groupEntity.setId(26L);

        when(userService.resolveUser(from)).thenReturn(userEntity);
        when(groupService.resolveGroup(chat)).thenReturn(groupEntity);

        MessageEntity alreadySaved = new MessageEntity(groupEntity, userEntity, 509L, "Already saved text", Instant.now());
        alreadySaved.setId(36L);
        when(messageRepository.findByGroupIdAndTelegramMessageId(26L, 509L)).thenReturn(Optional.of(alreadySaved));

        ingestionService.ingestUpdate(update);

        verify(messageRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Null or empty updates are handled gracefully without exceptions")
    void nullOrMalformedUpdatesHandledSafely() {
        ingestionService.ingestUpdate(null);
        ingestionService.ingestUpdate(new UpdateDto(10L, null, null, null));
        verifyNoInteractions(userService);
        verifyNoInteractions(messageRepository);
    }
}
