package com.recallbot.persistence;

import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipEntity;
import com.recallbot.core.group.GroupMembershipRepository;
import com.recallbot.core.group.GroupRepository;
import com.recallbot.core.group.GroupRole;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.core.message.MessageType;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class MessageRepositoryIT extends BasePostgresIntegrationTest {

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GroupMembershipRepository groupMembershipRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @Transactional
    @DisplayName("Persist message and verify relations and generated tsvector column")
    void persistMessageAndVerifyTsvector() {
        GroupEntity group = groupRepository.save(new GroupEntity(-1001987654321L, "Architecture Group"));
        UserEntity user = userRepository.save(new UserEntity(987654321L, "alice_dev", "Alice", "Smith"));
        groupMembershipRepository.save(new GroupMembershipEntity(group, user, GroupRole.ADMIN));

        MessageEntity message = new MessageEntity(
                group,
                user,
                1001L,
                "We agreed to use PostgreSQL 16 with pgvector for our semantic memory store.",
                Instant.now()
        );
        message.setMessageType(MessageType.TEXT);
        MessageEntity saved = messageRepository.saveAndFlush(message);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getGroup().getId()).isEqualTo(group.getId());
        assertThat(saved.getUser().getId()).isEqualTo(user.getId());

        // Verify generated tsv_content in PostgreSQL
        String tsvContent = jdbcClient.sql("SELECT tsv_content::text FROM messages WHERE id = :id")
                .param("id", saved.getId())
                .query(String.class)
                .single();

        assertThat(tsvContent).contains("'postgresql':5", "'pgvector':8");
    }

    @Test
    @Transactional
    @DisplayName("Enforce unique constraint (group_id, telegram_message_id)")
    void enforceUniqueTelegramMessageIdPerGroup() {
        GroupEntity group = groupRepository.save(new GroupEntity(-1001112223334L, "Duplicate Test Group"));
        UserEntity user = userRepository.save(new UserEntity(111222333L, "bob_user", "Bob", null));

        MessageEntity msg1 = new MessageEntity(group, user, 555L, "First instance", Instant.now());
        messageRepository.saveAndFlush(msg1);

        MessageEntity msg2 = new MessageEntity(group, user, 555L, "Duplicate instance", Instant.now());
        assertThatThrownBy(() -> messageRepository.saveAndFlush(msg2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    @DisplayName("Find messages by group ordered by sent_at descending")
    void findMessagesByGroupOrderedBySentAtDesc() {
        GroupEntity group = groupRepository.save(new GroupEntity(-1009988776655L, "Paging Test Group"));
        UserEntity user = userRepository.save(new UserEntity(998877665L, "carol_user", "Carol", "Danvers"));

        Instant now = Instant.now();
        messageRepository.save(new MessageEntity(group, user, 1L, "Old message", now.minusSeconds(120)));
        messageRepository.save(new MessageEntity(group, user, 2L, "Mid message", now.minusSeconds(60)));
        messageRepository.save(new MessageEntity(group, user, 3L, "New message", now));
        messageRepository.flush();

        List<MessageEntity> messages = messageRepository.findByGroupIdOrderBySentAtDesc(group.getId(), PageRequest.of(0, 2));

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getContent()).isEqualTo("New message");
        assertThat(messages.get(1).getContent()).isEqualTo("Mid message");
    }
}
