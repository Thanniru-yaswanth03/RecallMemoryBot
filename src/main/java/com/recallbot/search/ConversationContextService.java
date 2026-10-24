package com.recallbot.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Manages conversational context across sequential queries within a Telegram group.
 * Resolves follow-up questions ("What else?", "Who was assigned that?", "List remaining tasks")
 * by preserving the conversational anchor and excluding previously retrieved message IDs
 * so additional distinct memories are discovered without repetitive loops.
 */
@Service
public class ConversationContextService {

    private static final Logger log = LoggerFactory.getLogger(ConversationContextService.class);
    private static final Duration CONTEXT_TTL = Duration.ofMinutes(15);

    private final Map<Long, GroupConversationContext> contextStore = new ConcurrentHashMap<>();

    private static final Pattern FOLLOW_UP_PATTERN = Pattern.compile(
            "^(what else|anything else|there are more|tell me more|more tasks|list remaining|remaining tasks|rest of them|and then\\??|" +
                    "who was assigned that|who is doing that|who is responsible|when did we discuss it|when was that decided|what about yesterday's decision|" +
                    "aur kya|aur batao|kuch aur|aur bhi hai|baaki tasks|baki kya hai|" +
                    "inka emaina unnaaya|inkenti|inka|migilina tasks|marikoni|inka em unnay)\\b.*$",
            Pattern.CASE_INSENSITIVE
    );

    public record GroupConversationContext(
            Long groupId,
            String lastQuestion,
            Set<Long> retrievedMessageIds,
            Instant lastQueryTime,
            boolean isMultiItem
    ) {}

    public record FollowUpResolution(
            boolean isFollowUp,
            String effectiveQuery,
            Set<Long> excludedMessageIds,
            String previousQuestionContext
    ) {}

    /**
     * Resolves whether the current question is a follow-up to previous group discussion.
     *
     * @param groupId  the group database ID
     * @param question the current user question
     * @return FollowUpResolution with synthesized query and excluded message IDs
     */
    public FollowUpResolution resolveFollowUp(Long groupId, String question) {
        if (groupId == null || question == null || question.isBlank()) {
            return new FollowUpResolution(false, question, Collections.emptySet(), null);
        }

        GroupConversationContext existing = contextStore.get(groupId);
        if (existing == null) {
            return new FollowUpResolution(false, question, Collections.emptySet(), null);
        }

        // Expire stale context if past TTL
        if (Duration.between(existing.lastQueryTime(), Instant.now()).compareTo(CONTEXT_TTL) > 0) {
            contextStore.remove(groupId);
            return new FollowUpResolution(false, question, Collections.emptySet(), null);
        }

        String trimmed = question.trim();
        boolean matchesFollowUp = FOLLOW_UP_PATTERN.matcher(trimmed).find()
                || trimmed.equalsIgnoreCase("more")
                || trimmed.equalsIgnoreCase("what else?")
                || trimmed.equalsIgnoreCase("what else");

        if (matchesFollowUp) {
            String merged = existing.lastQuestion() + " " + trimmed;
            log.info("[Group #{}] Follow-up detected: '{}' -> merged with previous context: '{}', excluding {} previously retrieved IDs",
                    groupId, trimmed, merged, existing.retrievedMessageIds().size());

            return new FollowUpResolution(
                    true,
                    merged,
                    Collections.unmodifiableSet(new HashSet<>(existing.retrievedMessageIds())),
                    existing.lastQuestion()
            );
        }

        return new FollowUpResolution(false, question, Collections.emptySet(), null);
    }

    /**
     * Records or updates the group conversation context after a query is processed.
     *
     * @param groupId           the group database ID
     * @param question          the question that was answered
     * @param isFollowUp        whether this question was a follow-up
     * @param retrievedMessageIds the message IDs retrieved in this turn
     * @param isMultiItem       whether this question was a multi-item query
     */
    public void recordTurn(
            Long groupId,
            String question,
            boolean isFollowUp,
            Collection<Long> retrievedMessageIds,
            boolean isMultiItem
    ) {
        if (groupId == null || question == null) {
            return;
        }

        Set<Long> messageIds = new HashSet<>();
        if (isFollowUp) {
            GroupConversationContext prev = contextStore.get(groupId);
            if (prev != null) {
                messageIds.addAll(prev.retrievedMessageIds());
            }
        }
        if (retrievedMessageIds != null) {
            messageIds.addAll(retrievedMessageIds);
        }

        contextStore.put(groupId, new GroupConversationContext(
                groupId,
                question.trim(),
                messageIds,
                Instant.now(),
                isMultiItem
        ));

        log.debug("[Group #{}] Recorded conversation context turn: total tracked message IDs={}",
                groupId, messageIds.size());
    }

    /**
     * Clears conversation context for a group (e.g. on group erasure or privacy command).
     *
     * @param groupId the group ID
     */
    public void clearContext(Long groupId) {
        if (groupId != null) {
            contextStore.remove(groupId);
        }
    }
}
