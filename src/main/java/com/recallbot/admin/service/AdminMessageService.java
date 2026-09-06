package com.recallbot.admin.service;

import com.recallbot.admin.dto.AdminMessageDto;
import com.recallbot.admin.dto.PageResponse;
import com.recallbot.core.message.MessageEmbeddingRepository;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.core.message.MessageRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminMessageService {

    private final MessageRepository messageRepository;
    private final MessageEmbeddingRepository messageEmbeddingRepository;

    public AdminMessageService(
            MessageRepository messageRepository,
            MessageEmbeddingRepository messageEmbeddingRepository
    ) {
        this.messageRepository = messageRepository;
        this.messageEmbeddingRepository = messageEmbeddingRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminMessageDto> getMessages(int page, int size, Long groupId, Long userId, String query) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));
        Page<MessageEntity> messagePage;
        if (query != null && !query.isBlank()) {
            String queryPattern = "%" + query.trim().toLowerCase() + "%";
            messagePage = messageRepository.searchMessages(groupId, userId, queryPattern, pageable);
        } else {
            messagePage = messageRepository.findFilteredMessages(groupId, userId, pageable);
        }

        List<AdminMessageDto> dtos = messagePage.getContent().stream().map(msg -> {
            boolean hasEmbedding = messageEmbeddingRepository.findByMessageId(msg.getId()).isPresent();
            return new AdminMessageDto(
                    msg.getId(),
                    msg.getGroup() != null ? msg.getGroup().getId() : null,
                    msg.getGroup() != null ? msg.getGroup().getTitle() : "Unknown",
                    msg.getUser() != null ? msg.getUser().getId() : null,
                    msg.getUser() != null ? msg.getUser().getFirstName() : "Unknown",
                    msg.getUser() != null ? msg.getUser().getUsername() : null,
                    msg.getTelegramMessageId(),
                    msg.getReplyToTelegramMessageId(),
                    sanitize(msg.getContent()),
                    msg.getMessageType() != null ? msg.getMessageType().name() : "TEXT",
                    hasEmbedding,
                    msg.getSentAt(),
                    msg.getEditedAt()
            );
        }).toList();

        return PageResponse.of(dtos, messagePage.getNumber(), messagePage.getSize(), messagePage.getTotalElements());
    }

    private String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.length() > 200 ? text.substring(0, 197) + "..." : text;
        return trimmed.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }
}
