package com.recallbot.core.group;

import com.recallbot.core.user.UserEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Service managing user memberships and administrative roles within groups.
 */
@Service
public class GroupMembershipService {

    private static final Logger log = LoggerFactory.getLogger(GroupMembershipService.class);

    private final GroupMembershipRepository membershipRepository;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

    public GroupMembershipService(GroupMembershipRepository membershipRepository, org.springframework.jdbc.core.simple.JdbcClient jdbcClient) {
        this.membershipRepository = membershipRepository;
        this.jdbcClient = jdbcClient;
    }

    /**
     * Ensures that a group membership relation exists for the given user in the given group.
     * Idempotent and concurrency-safe.
     *
     * @param group the persistent group
     * @param user  the persistent user
     * @param role  the role (defaults to MEMBER if null)
     * @return the existing or newly created GroupMembershipEntity
     */
    @Transactional
    public GroupMembershipEntity ensureMembership(GroupEntity group, UserEntity user, GroupRole role) {
        if (group == null || group.getId() == null || user == null || user.getId() == null) {
            throw new IllegalArgumentException("Group and User must both be persisted entities with valid IDs");
        }

        Long groupId = group.getId();
        Long userId = user.getId();
        GroupRole effectiveRole = role != null ? role : GroupRole.MEMBER;

        Long id = jdbcClient.sql("""
                INSERT INTO group_memberships (group_id, user_id, role, joined_at, updated_at)
                VALUES (:groupId, :userId, :role, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (group_id, user_id) DO UPDATE SET
                    updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """)
                .param("groupId", groupId)
                .param("userId", userId)
                .param("role", effectiveRole.name())
                .query(Long.class)
                .single();

        return membershipRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Failed to load membership with id=" + id));
    }

    @Transactional(readOnly = true)
    public boolean isMember(Long groupId, Long userId) {
        return membershipRepository.findByGroupIdAndUserId(groupId, userId).isPresent();
    }

    @Transactional(readOnly = true)
    public boolean isGroupAdmin(Long groupId, Long userId) {
        if (groupId == null || userId == null) {
            return false;
        }
        return membershipRepository.findByGroupIdAndUserId(groupId, userId)
                .map(m -> m.getRole() == GroupRole.ADMIN || m.getRole() == GroupRole.CREATOR)
                .orElse(false);
    }

    @Transactional
    public void removeMembership(Long groupId, Long userId) {
        if (groupId == null || userId == null) {
            return;
        }
        membershipRepository.deleteByGroupIdAndUserId(groupId, userId);
        log.debug("Removed membership for group_id={}, user_id={}", groupId, userId);
    }
}
