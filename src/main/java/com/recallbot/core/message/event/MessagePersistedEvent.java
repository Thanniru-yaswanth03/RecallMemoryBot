package com.recallbot.core.message.event;

/**
 * Domain event published when a Telegram message has been successfully persisted or edited
 * in the database. Consumed by downstream asynchronous workers for vector embedding (Phase 4)
 * and memory extraction (Phase 7).
 *
 * @param messageId internal primary key of the persisted message
 * @param groupId   internal primary key of the group
 * @param isEdit    true if this event represents an edited message, false for new messages
 */
public record MessagePersistedEvent(
        Long messageId,
        Long groupId,
        boolean isEdit
) {}
