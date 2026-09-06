package com.recallbot.memory;

import com.recallbot.core.message.MessageEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Background worker responsible for extracting decisions, commitments,
 * and key factual consensus from conversational message batches.
 */
@Component
public class MemoryExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionWorker.class);

    private static final Pattern DECISION_PATTERN = Pattern.compile(
            "\\b(we decided|decided to|let's go with|agreed to|agreed that|finalized|chosen|consensus is)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern COMMITMENT_PATTERN = Pattern.compile(
            "\\b(i will|i'll|i promise to|assigned to|volunteered to|taking care of|responsible for)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final MemoryService memoryService;

    public MemoryExtractionWorker(MemoryService memoryService) {
        this.memoryService = Objects.requireNonNull(memoryService, "memoryService must not be null");
    }

    /**
     * Inspects a batch of messages for a group and extracts derived memories.
     *
     * @param groupId  the group database ID
     * @param messages the messages to evaluate
     * @return the list of newly created derived MemoryEntity records
     */
    public List<MemoryEntity> extractMemoriesFromBatch(Long groupId, List<MessageEntity> messages) {
        if (groupId == null || messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<MemoryEntity> extracted = new ArrayList<>();

        for (MessageEntity message : messages) {
            String content = message.getContent();
            if (content == null || content.isBlank() || content.length() < 10) {
                continue;
            }

            if (DECISION_PATTERN.matcher(content).find()) {
                MemoryEntity memory = memoryService.recordDerivedMemory(
                        groupId,
                        MemoryType.DECISION,
                        content.trim(),
                        new BigDecimal("0.90"),
                        List.of(message.getId())
                );
                extracted.add(memory);
            } else if (COMMITMENT_PATTERN.matcher(content).find()) {
                MemoryEntity memory = memoryService.recordDerivedMemory(
                        groupId,
                        MemoryType.COMMITMENT,
                        content.trim(),
                        new BigDecimal("0.85"),
                        List.of(message.getId())
                );
                extracted.add(memory);
            }
        }

        if (!extracted.isEmpty()) {
            log.info("Extracted {} derived memories from batch of {} messages for group_id={}",
                    extracted.size(), messages.size(), groupId);
        }
        return extracted;
    }
}
