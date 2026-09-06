package com.recallbot.admin.service;

import com.recallbot.admin.dto.AdminMemoryDto;
import com.recallbot.admin.dto.MemorySourceDto;
import com.recallbot.admin.dto.PageResponse;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.memory.MemoryEntity;
import com.recallbot.memory.MemoryRepository;
import com.recallbot.memory.MemorySourceEntity;
import com.recallbot.memory.MemorySourceRepository;
import com.recallbot.memory.MemoryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminMemoryService {

    private final MemoryRepository memoryRepository;
    private final MemorySourceRepository memorySourceRepository;

    public AdminMemoryService(
            MemoryRepository memoryRepository,
            MemorySourceRepository memorySourceRepository
    ) {
        this.memoryRepository = memoryRepository;
        this.memorySourceRepository = memorySourceRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminMemoryDto> getMemories(int page, int size, Long groupId, String memoryTypeStr, String query) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));

        MemoryType memoryType = null;
        if (memoryTypeStr != null && !memoryTypeStr.isBlank()) {
            try {
                memoryType = MemoryType.valueOf(memoryTypeStr.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Ignore invalid memory type filter
            }
        }

        Page<MemoryEntity> memoryPage;
        if (query != null && !query.isBlank()) {
            String queryPattern = "%" + query.trim().toLowerCase() + "%";
            memoryPage = memoryRepository.searchMemories(groupId, memoryType, queryPattern, pageable);
        } else {
            memoryPage = memoryRepository.findFilteredMemories(groupId, memoryType, pageable);
        }

        List<AdminMemoryDto> dtos = memoryPage.getContent().stream().map(mem -> {
            List<MemorySourceEntity> sources = memorySourceRepository.findByMemoryId(mem.getId());
            List<MemorySourceDto> sourceDtos = sources.stream().map(src -> {
                MessageEntity msg = src.getMessage();
                return new MemorySourceDto(
                        msg != null ? msg.getId() : null,
                        msg != null ? msg.getTelegramMessageId() : null,
                        (msg != null && msg.getUser() != null) ? msg.getUser().getFirstName() : "Unknown",
                        (msg != null && msg.getUser() != null) ? msg.getUser().getUsername() : null,
                        msg != null ? msg.getSentAt() : null
                );
            }).toList();

            return new AdminMemoryDto(
                    mem.getId(),
                    mem.getGroup() != null ? mem.getGroup().getId() : null,
                    mem.getGroup() != null ? mem.getGroup().getTitle() : "Unknown",
                    mem.getMemoryType() != null ? mem.getMemoryType().name() : "FACT",
                    mem.getContent(),
                    mem.getConfidence(),
                    mem.getModelName(),
                    mem.getCreatedAt(),
                    mem.getUpdatedAt(),
                    sourceDtos
            );
        }).toList();

        return PageResponse.of(dtos, memoryPage.getNumber(), memoryPage.getSize(), memoryPage.getTotalElements());
    }
}
