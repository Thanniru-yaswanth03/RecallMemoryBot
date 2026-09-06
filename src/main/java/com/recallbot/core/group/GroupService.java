package com.recallbot.core.group;

import com.recallbot.telegram.dto.ChatDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Service managing Telegram group chats in the core domain.
 */
@Service
public class GroupService {

    private static final Logger log = LoggerFactory.getLogger(GroupService.class);

    private final GroupRepository groupRepository;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

    public GroupService(GroupRepository groupRepository, org.springframework.jdbc.core.simple.JdbcClient jdbcClient) {
        this.groupRepository = groupRepository;
        this.jdbcClient = jdbcClient;
    }

    /**
     * Resolves a Telegram ChatDto to a persistent GroupEntity.
     * Atomically creates or updates group using PostgreSQL ON CONFLICT RETURNING id.
     * Safe under concurrent multi-threaded delivery.
     *
     * @param chatDto the Telegram chat DTO (must be group or supergroup)
     * @return the resolved, managed GroupEntity
     */
    @Transactional
    public GroupEntity resolveGroup(ChatDto chatDto) {
        if (chatDto == null || chatDto.id() == null) {
            throw new IllegalArgumentException("ChatDto and chat ID must not be null");
        }

        Long telegramChatId = chatDto.id();
        String title = chatDto.title() != null && !chatDto.title().isBlank()
                ? chatDto.title()
                : "Group " + telegramChatId;

        Long id = jdbcClient.sql("""
                INSERT INTO groups (telegram_chat_id, title, is_active, created_at, updated_at)
                VALUES (:telegramChatId, :title, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (telegram_chat_id) DO UPDATE SET
                    title = EXCLUDED.title,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """)
                .param("telegramChatId", telegramChatId)
                .param("title", title)
                .query(Long.class)
                .single();

        return groupRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Failed to load group with id=" + id));
    }

    @Transactional(readOnly = true)
    public Optional<GroupEntity> findByTelegramChatId(Long telegramChatId) {
        return groupRepository.findByTelegramChatId(telegramChatId);
    }
}
