package com.recallbot.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deduplicator that guarantees each Telegram update_id is processed at most once.
 * Leverages PostgreSQL's primary key constraint with ON CONFLICT DO NOTHING
 * for thread-safe and cluster-safe atomic acquisition.
 */
@Component
public class TelegramUpdateDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateDeduplicator.class);

    private final JdbcClient jdbcClient;

    public TelegramUpdateDeduplicator(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Attempts to acquire processing rights for an update_id.
     *
     * @param updateId the Telegram update_id
     * @param groupId  optional internal group_id (null if not yet resolved or direct message)
     * @return true if successfully acquired (new update), false if duplicate
     */
    @Transactional
    public boolean tryAcquire(Long updateId, Long groupId) {
        if (updateId == null) {
            return false;
        }

        int rows = jdbcClient.sql("""
                INSERT INTO telegram_updates (update_id, group_id, status, received_at, processed_at)
                VALUES (:updateId, :groupId, 'PROCESSED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (update_id) DO NOTHING
                """)
                .param("updateId", updateId)
                .param("groupId", groupId)
                .update();

        if (rows > 0) {
            log.debug("Acquired update_id={}", updateId);
            return true;
        } else {
            log.info("Duplicate Telegram update detected and suppressed: update_id={}", updateId);
            return false;
        }
    }

    /**
     * Marks an update as ignored (e.g. unsupported update type).
     */
    @Transactional
    public void markIgnored(Long updateId, String reason) {
        if (updateId == null) return;
        jdbcClient.sql("""
                UPDATE telegram_updates
                SET status = 'IGNORED', error_code = :reason, processed_at = CURRENT_TIMESTAMP
                WHERE update_id = :updateId
                """)
                .param("reason", reason)
                .param("updateId", updateId)
                .update();
    }

    /**
     * Marks an update as failed.
     */
    @Transactional
    public void markFailed(Long updateId, String errorCode) {
        if (updateId == null) return;
        jdbcClient.sql("""
                UPDATE telegram_updates
                SET status = 'FAILED', error_code = :errorCode, processed_at = CURRENT_TIMESTAMP
                WHERE update_id = :updateId
                """)
                .param("errorCode", errorCode)
                .param("updateId", updateId)
                .update();
    }

    /**
     * Associates the internal group_id with an update record once resolved.
     */
    @Transactional
    public void associateGroupId(Long updateId, Long groupId) {
        if (updateId == null || groupId == null) return;
        jdbcClient.sql("UPDATE telegram_updates SET group_id = :groupId WHERE update_id = :updateId")
                .param("groupId", groupId)
                .param("updateId", updateId)
                .update();
    }
}
