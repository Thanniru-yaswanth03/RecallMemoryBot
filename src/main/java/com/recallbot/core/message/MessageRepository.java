package com.recallbot.core.message;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

    Optional<MessageEntity> findByGroupIdAndTelegramMessageId(Long groupId, Long telegramMessageId);

    boolean existsByGroupIdAndTelegramMessageId(Long groupId, Long telegramMessageId);

    List<MessageEntity> findByGroupIdOrderBySentAtDesc(Long groupId, Pageable pageable);

    List<MessageEntity> findByGroupId(Long groupId);

    List<MessageEntity> findByGroupIdAndUserId(Long groupId, Long userId);

    long countByGroupId(Long groupId);

    Page<MessageEntity> findByGroupId(Long groupId, Pageable pageable);

    @Query("""
        SELECT m FROM MessageEntity m
        WHERE (:groupId IS NULL OR m.group.id = :groupId)
          AND (:userId IS NULL OR m.user.id = :userId)
        ORDER BY m.sentAt DESC
    """)
    Page<MessageEntity> findFilteredMessages(
            @Param("groupId") Long groupId,
            @Param("userId") Long userId,
            Pageable pageable
    );

    @Query("""
        SELECT m FROM MessageEntity m
        WHERE (:groupId IS NULL OR m.group.id = :groupId)
          AND (:userId IS NULL OR m.user.id = :userId)
          AND LOWER(m.content) LIKE :queryPattern
        ORDER BY m.sentAt DESC
    """)
    Page<MessageEntity> searchMessages(
            @Param("groupId") Long groupId,
            @Param("userId") Long userId,
            @Param("queryPattern") String queryPattern,
            Pageable pageable
    );
}
