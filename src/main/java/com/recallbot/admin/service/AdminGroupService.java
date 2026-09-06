package com.recallbot.admin.service;

import com.recallbot.admin.dto.AdminGroupDetailDto;
import com.recallbot.admin.dto.AdminGroupDto;
import com.recallbot.admin.dto.AdminMemberDto;
import com.recallbot.admin.dto.AdminMemoryDto;
import com.recallbot.admin.dto.AdminMessageDto;
import com.recallbot.admin.dto.MemorySourceDto;
import com.recallbot.admin.dto.PageResponse;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipEntity;
import com.recallbot.core.group.GroupMembershipRepository;
import com.recallbot.core.group.GroupRepository;
import com.recallbot.core.message.MessageEmbeddingRepository;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserRepository;
import com.recallbot.memory.MemoryEntity;
import com.recallbot.memory.MemoryRepository;
import com.recallbot.memory.MemorySourceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AdminGroupService {

    private final GroupRepository groupRepository;
    private final GroupMembershipRepository groupMembershipRepository;
    private final MessageRepository messageRepository;
    private final MemoryRepository memoryRepository;
    private final MessageEmbeddingRepository messageEmbeddingRepository;
    private final MemorySourceRepository memorySourceRepository;
    private final UserRepository userRepository;

    public AdminGroupService(
            GroupRepository groupRepository,
            GroupMembershipRepository groupMembershipRepository,
            MessageRepository messageRepository,
            MemoryRepository memoryRepository,
            MessageEmbeddingRepository messageEmbeddingRepository,
            MemorySourceRepository memorySourceRepository,
            UserRepository userRepository
    ) {
        this.groupRepository = groupRepository;
        this.groupMembershipRepository = groupMembershipRepository;
        this.messageRepository = messageRepository;
        this.memoryRepository = memoryRepository;
        this.messageEmbeddingRepository = messageEmbeddingRepository;
        this.memorySourceRepository = memorySourceRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminGroupDto> getGroups(int page, int size, String search, Boolean active) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)), Sort.by("id").descending());
        Page<GroupEntity> groupPage;

        if (search != null && !search.isBlank()) {
            groupPage = groupRepository.findByTitleContainingIgnoreCase(search.trim(), pageable);
        } else {
            groupPage = groupRepository.findAll(pageable);
        }

        List<AdminGroupDto> dtos = groupPage.getContent().stream()
                .filter(g -> active == null || g.isActive() == active)
                .map(this::mapToGroupDto)
                .toList();

        return PageResponse.of(dtos, groupPage.getNumber(), groupPage.getSize(), groupPage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Optional<AdminGroupDetailDto> getGroupDetails(Long groupId) {
        Optional<GroupEntity> groupOpt = groupRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return Optional.empty();
        }

        GroupEntity group = groupOpt.get();
        AdminGroupDto groupDto = mapToGroupDto(group);
        long embeddingCount = messageEmbeddingRepository.countByGroupId(groupId);

        // Fetch top members
        List<GroupMembershipEntity> memberships = groupMembershipRepository.findByGroupId(groupId);
        Map<Long, UserEntity> usersById = userRepository.findAllById(
                memberships.stream().map(GroupMembershipEntity::getUserId).toList()
        ).stream().collect(Collectors.toMap(UserEntity::getId, u -> u));

        List<AdminMemberDto> memberDtos = memberships.stream().map(m -> {
            UserEntity user = usersById.get(m.getUserId());
            return new AdminMemberDto(
                    m.getId(),
                    m.getUserId(),
                    user != null ? user.getTelegramUserId() : null,
                    user != null ? user.getUsername() : null,
                    user != null ? user.getFirstName() : "Unknown",
                    user != null ? user.getLastName() : null,
                    m.getRole() != null ? m.getRole().name() : "MEMBER",
                    m.getJoinedAt()
            );
        }).toList();

        // Recent 10 messages
        List<MessageEntity> recentMessages = messageRepository.findByGroupIdOrderBySentAtDesc(
                groupId, PageRequest.of(0, 10)
        );
        List<AdminMessageDto> recentMessageDtos = recentMessages.stream().map(msg ->
                new AdminMessageDto(
                        msg.getId(),
                        group.getId(),
                        group.getTitle(),
                        msg.getUser() != null ? msg.getUser().getId() : null,
                        msg.getUser() != null ? msg.getUser().getFirstName() : "Unknown",
                        msg.getUser() != null ? msg.getUser().getUsername() : null,
                        msg.getTelegramMessageId(),
                        msg.getReplyToTelegramMessageId(),
                        sanitizeContentSnippet(msg.getContent()),
                        msg.getMessageType() != null ? msg.getMessageType().name() : "TEXT",
                        true,
                        msg.getSentAt(),
                        msg.getEditedAt()
                )
        ).toList();

        // Recent 10 memories
        List<MemoryEntity> recentMemories = memoryRepository.findTop10ByGroupIdOrderByCreatedAtDesc(groupId);
        List<AdminMemoryDto> recentMemoryDtos = recentMemories.stream().map(mem ->
                new AdminMemoryDto(
                        mem.getId(),
                        group.getId(),
                        group.getTitle(),
                        mem.getMemoryType() != null ? mem.getMemoryType().name() : "FACT",
                        mem.getContent(),
                        mem.getConfidence(),
                        mem.getModelName(),
                        mem.getCreatedAt(),
                        mem.getUpdatedAt(),
                        List.of()
                )
        ).toList();

        return Optional.of(new AdminGroupDetailDto(
                groupDto,
                embeddingCount,
                memberDtos,
                recentMessageDtos,
                recentMemoryDtos
        ));
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminMemberDto> getGroupMembers(Long groupId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));
        Page<GroupMembershipEntity> membershipPage = groupMembershipRepository.findByGroupId(groupId, pageable);

        Map<Long, UserEntity> usersById = userRepository.findAllById(
                membershipPage.getContent().stream().map(GroupMembershipEntity::getUserId).toList()
        ).stream().collect(Collectors.toMap(UserEntity::getId, u -> u));

        List<AdminMemberDto> dtos = membershipPage.getContent().stream().map(m -> {
            UserEntity user = usersById.get(m.getUserId());
            return new AdminMemberDto(
                    m.getId(),
                    m.getUserId(),
                    user != null ? user.getTelegramUserId() : null,
                    user != null ? user.getUsername() : null,
                    user != null ? user.getFirstName() : "Unknown",
                    user != null ? user.getLastName() : null,
                    m.getRole() != null ? m.getRole().name() : "MEMBER",
                    m.getJoinedAt()
            );
        }).toList();

        return PageResponse.of(dtos, membershipPage.getNumber(), membershipPage.getSize(), membershipPage.getTotalElements());
    }

    private AdminGroupDto mapToGroupDto(GroupEntity group) {
        long memberCount = groupMembershipRepository.countByGroupId(group.getId());
        long messageCount = messageRepository.countByGroupId(group.getId());
        long memoryCount = memoryRepository.countByGroupId(group.getId());
        Instant lastActivity = group.getUpdatedAt() != null ? group.getUpdatedAt() : group.getCreatedAt();

        return new AdminGroupDto(
                group.getId(),
                group.getTelegramChatId(),
                group.getTitle(),
                group.isActive(),
                group.getRetentionDays(),
                memberCount,
                messageCount,
                memoryCount,
                lastActivity,
                group.getCreatedAt()
        );
    }

    private String sanitizeContentSnippet(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.length() > 120 ? content.substring(0, 117) + "..." : content;
        return trimmed.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }
}
