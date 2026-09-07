package com.recallbot.core.message;

import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipEntity;
import com.recallbot.core.group.GroupMembershipRepository;
import com.recallbot.core.group.GroupRepository;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserRepository;
import com.recallbot.persistence.BasePostgresIntegrationTest;
import com.recallbot.telegram.dto.ChatDto;
import com.recallbot.telegram.dto.MessageDto;
import com.recallbot.telegram.dto.UpdateDto;
import com.recallbot.telegram.dto.UserDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MessageIngestionIT extends BasePostgresIntegrationTest {

    @Autowired
    private MessageIngestionService ingestionService;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GroupMembershipRepository membershipRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanUp() {
        jdbcClient.sql("DELETE FROM message_embeddings").update();
        jdbcClient.sql("DELETE FROM memory_sources").update();
        jdbcClient.sql("DELETE FROM memories").update();
        jdbcClient.sql("DELETE FROM messages").update();
        jdbcClient.sql("DELETE FROM group_memberships").update();
        jdbcClient.sql("DELETE FROM groups").update();
        jdbcClient.sql("DELETE FROM users").update();
        jdbcClient.sql("DELETE FROM telegram_updates").update();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    @DisplayName("End-to-end message ingestion persists group, user, membership, and message")
    void fullMessageIngestionPipeline() {
        Long telegramUserId = 12345678L;
        Long telegramChatId = -100123456789L;
        Long telegramMsgId = 7001L;

        UserDto userDto = new UserDto(telegramUserId, false, "Sarah", "Connor", "sarah_c");
        ChatDto chatDto = new ChatDto(telegramChatId, "supergroup", "Resistance HQ", "resistance_hq");
        MessageDto messageDto = new MessageDto(telegramMsgId, userDto, chatDto, 1690000000L,
                "We need to protect the timeline at all costs.", null, null);
        UpdateDto updateDto = new UpdateDto(9001L, messageDto, null, null);

        ingestionService.ingestUpdate(updateDto);

        // Verify group
        Optional<GroupEntity> groupOpt = groupRepository.findByTelegramChatId(telegramChatId);
        assertThat(groupOpt).isPresent();
        GroupEntity group = groupOpt.get();
        assertThat(group.getTitle()).isEqualTo("Resistance HQ");

        // Verify user
        Optional<UserEntity> userOpt = userRepository.findByTelegramUserId(telegramUserId);
        assertThat(userOpt).isPresent();
        UserEntity user = userOpt.get();
        assertThat(user.getUsername()).isEqualTo("sarah_c");
        assertThat(user.getFirstName()).isEqualTo("Sarah");

        // Verify membership
        Optional<GroupMembershipEntity> membershipOpt = membershipRepository.findByGroupIdAndUserId(group.getId(), user.getId());
        assertThat(membershipOpt).isPresent();

        // Verify message
        Optional<MessageEntity> msgOpt = messageRepository.findByGroupIdAndTelegramMessageId(group.getId(), telegramMsgId);
        assertThat(msgOpt).isPresent();
        MessageEntity message = msgOpt.get();
        assertThat(message.getContent()).isEqualTo("We need to protect the timeline at all costs.");
        assertThat(message.getUser().getId()).isEqualTo(user.getId());
        assertThat(message.getGroup().getId()).isEqualTo(group.getId());

        // Verify generated tsvector column in PostgreSQL
        String tsvContent = jdbcClient.sql("SELECT tsv_content::text FROM messages WHERE id = :id")
                .param("id", message.getId())
                .query(String.class)
                .single();
        assertThat(tsvContent).isNotBlank();
        assertThat(tsvContent).contains("'protect'");
        assertThat(tsvContent).contains("'timelin'");
    }

    @Test
    @DisplayName("Subsequent messages from same user in same group reuse user and group without duplicates")
    void userAndGroupReusedOnSubsequentMessages() {
        Long telegramUserId = 22334455L;
        Long telegramChatId = -10099887766L;

        UserDto userDto = new UserDto(telegramUserId, false, "John", "Doe", "jdoe");
        ChatDto chatDto = new ChatDto(telegramChatId, "supergroup", "Project Alpha", null);

        MessageDto msg1 = new MessageDto(8001L, userDto, chatDto, 1690000010L, "First message", null, null);
        MessageDto msg2 = new MessageDto(8002L, userDto, chatDto, 1690000020L, "Second message", null, null);

        ingestionService.ingestUpdate(new UpdateDto(9101L, msg1, null, null));
        ingestionService.ingestUpdate(new UpdateDto(9102L, msg2, null, null));

        // Ensure only 1 user and 1 group exist
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(groupRepository.count()).isEqualTo(1);
        assertThat(membershipRepository.count()).isEqualTo(1);

        // Ensure 2 messages exist
        assertThat(messageRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Duplicate message delivery is idempotent and does not create duplicate message rows")
    void duplicateMessageDeliveryIsIdempotent() {
        Long telegramUserId = 33445566L;
        Long telegramChatId = -10044556677L;
        Long telegramMsgId = 8003L;

        UserDto userDto = new UserDto(telegramUserId, false, "Mark", null, "mark_dev");
        ChatDto chatDto = new ChatDto(telegramChatId, "group", "Beta Group", null);
        MessageDto msg = new MessageDto(telegramMsgId, userDto, chatDto, 1690000030L, "Idempotent test message", null, null);

        ingestionService.ingestUpdate(new UpdateDto(9201L, msg, null, null));
        ingestionService.ingestUpdate(new UpdateDto(9202L, msg, null, null)); // Re-delivery of same message

        assertThat(messageRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Edited message update updates content and edited_at timestamp")
    void editedMessageUpdatesExistingRow() {
        Long telegramUserId = 44556677L;
        Long telegramChatId = -10055667788L;
        Long telegramMsgId = 8004L;

        UserDto userDto = new UserDto(telegramUserId, false, "Lisa", null, "lisa_arch");
        ChatDto chatDto = new ChatDto(telegramChatId, "supergroup", "Design Group", null);

        MessageDto originalMsg = new MessageDto(telegramMsgId, userDto, chatDto, 1690000040L, "Original draft", null, null);
        ingestionService.ingestUpdate(new UpdateDto(9301L, originalMsg, null, null));

        MessageDto editedMsg = new MessageDto(telegramMsgId, userDto, chatDto, 1690000050L, "Final approved draft", null, null);
        ingestionService.ingestUpdate(new UpdateDto(9302L, null, editedMsg, null));

        assertThat(messageRepository.count()).isEqualTo(1);
        GroupEntity group = groupRepository.findByTelegramChatId(telegramChatId).orElseThrow();
        MessageEntity message = messageRepository.findByGroupIdAndTelegramMessageId(group.getId(), telegramMsgId).orElseThrow();

        assertThat(message.getContent()).isEqualTo("Final approved draft");
        assertThat(message.getEditedAt()).isNotNull();
    }

    @Test
    @DisplayName("Concurrent message ingestion from multiple threads executes safely without deadlock or duplicate entities")
    void concurrentMessageIngestionSafe() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        Long telegramChatId = -10066778899L;
        ChatDto chatDto = new ChatDto(telegramChatId, "supergroup", "Concurrent Group", null);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            tasks.add(() -> {
                UserDto userDto = new UserDto(50000L + index, false, "User" + index, null, "user_" + index);
                MessageDto message = new MessageDto(90000L + index, userDto, chatDto, 1690000000L + index,
                        "Concurrent message payload " + index, null, null);
                UpdateDto update = new UpdateDto(99000L + index, message, null, null);
                ingestionService.ingestUpdate(update);
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        for (Future<Void> future : futures) {
            future.get();
        }

        assertThat(groupRepository.count()).isEqualTo(1);
        assertThat(userRepository.count()).isEqualTo(threadCount);
        assertThat(membershipRepository.count()).isEqualTo(threadCount);
        assertThat(messageRepository.count()).isEqualTo(threadCount);
    }
}
