package com.recallbot.core.group;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface GroupMembershipRepository extends JpaRepository<GroupMembershipEntity, Long> {

    Optional<GroupMembershipEntity> findByGroupIdAndUserId(Long groupId, Long userId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT gm FROM GroupMembershipEntity gm, GroupEntity g, UserEntity u
        WHERE gm.groupId = g.id AND gm.userId = u.id
          AND g.telegramChatId = :telegramChatId AND u.telegramUserId = :telegramUserId
    """)
    Optional<GroupMembershipEntity> findByGroupTelegramChatIdAndUserTelegramUserId(
            @org.springframework.data.repository.query.Param("telegramChatId") Long telegramChatId,
            @org.springframework.data.repository.query.Param("telegramUserId") Long telegramUserId);

    List<GroupMembershipEntity> findByGroupId(Long groupId);

    List<GroupMembershipEntity> findByUserId(Long userId);

    void deleteByGroupIdAndUserId(Long groupId, Long userId);

    long countByGroupId(Long groupId);

    Page<GroupMembershipEntity> findByGroupId(Long groupId, Pageable pageable);
}
