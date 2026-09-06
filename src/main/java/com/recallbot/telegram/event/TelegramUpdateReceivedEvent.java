package com.recallbot.telegram.event;

import com.recallbot.telegram.dto.UpdateDto;

import java.time.Instant;

/**
 * Domain event published when an incoming Telegram update is successfully
 * authenticated, parsed, and deduplicated.
 */
public record TelegramUpdateReceivedEvent(
        UpdateDto update,
        Instant receivedAt
) {
    public TelegramUpdateReceivedEvent(UpdateDto update) {
        this(update, Instant.now());
    }
}
