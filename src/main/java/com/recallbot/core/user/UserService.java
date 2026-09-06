package com.recallbot.core.user;

import com.recallbot.telegram.dto.UserDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Service managing Telegram user identities in the core domain.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

    public UserService(UserRepository userRepository, org.springframework.jdbc.core.simple.JdbcClient jdbcClient) {
        this.userRepository = userRepository;
        this.jdbcClient = jdbcClient;
    }

    /**
     * Resolves a Telegram UserDto to a persistent UserEntity.
     * Updates profile fields if changed; creates a new record if absent.
     * Safe under concurrent delivery.
     *
     * @param userDto the Telegram user DTO
     * @return the resolved, managed UserEntity
     */
    @Transactional
    public UserEntity resolveUser(UserDto userDto) {
        if (userDto == null || userDto.id() == null) {
            throw new IllegalArgumentException("UserDto and user ID must not be null");
        }

        Long telegramUserId = userDto.id();
        String firstName = userDto.firstName() != null ? userDto.firstName() : "";
        boolean isBot = Boolean.TRUE.equals(userDto.isBot());

        Long id = jdbcClient.sql("""
                INSERT INTO users (telegram_user_id, username, first_name, last_name, is_bot, created_at, updated_at)
                VALUES (:telegramUserId, :username, :firstName, :lastName, :isBot, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (telegram_user_id) DO UPDATE SET
                    username = EXCLUDED.username,
                    first_name = EXCLUDED.first_name,
                    last_name = EXCLUDED.last_name,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """)
                .param("telegramUserId", telegramUserId)
                .param("username", userDto.username())
                .param("firstName", firstName)
                .param("lastName", userDto.lastName())
                .param("isBot", isBot)
                .query(Long.class)
                .single();

        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Failed to load user with id=" + id));
    }

    public static final Long ANONYMIZED_TELEGRAM_USER_ID = 0L;
    public static final String ANONYMIZED_FIRST_NAME = "[Former Member]";

    @Transactional(readOnly = true)
    public Optional<UserEntity> findByTelegramUserId(Long telegramUserId) {
        return userRepository.findByTelegramUserId(telegramUserId);
    }

    /**
     * Resolves or provisions the reserved system sentinel user representing anonymized former members.
     *
     * @return persistent UserEntity for [Former Member]
     */
    @Transactional
    public UserEntity getOrCreateAnonymizedUser() {
        Long id = jdbcClient.sql("""
                INSERT INTO users (telegram_user_id, username, first_name, last_name, is_bot, created_at, updated_at)
                VALUES (:telegramUserId, NULL, :firstName, NULL, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (telegram_user_id) DO UPDATE SET
                    first_name = EXCLUDED.first_name,
                    username = NULL,
                    last_name = NULL,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """)
                .param("telegramUserId", ANONYMIZED_TELEGRAM_USER_ID)
                .param("firstName", ANONYMIZED_FIRST_NAME)
                .query(Long.class)
                .single();

        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Failed to load anonymized user with id=" + id));
    }
}
