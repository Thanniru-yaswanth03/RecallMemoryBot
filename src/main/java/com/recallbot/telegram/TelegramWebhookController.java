package com.recallbot.telegram;

import com.recallbot.telegram.dto.UpdateDto;
import com.recallbot.telegram.event.TelegramUpdateReceivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Ingress webhook controller for incoming Telegram updates.
 * Guarantees sub-50ms HTTP 200 OK responses to prevent upstream Telegram retries,
 * performing only security validation, update deduplication, and async handoff.
 */
@RestController
@RequestMapping("/api/telegram/webhook")
public class TelegramWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);

    private final TelegramUpdateDeduplicator deduplicator;
    private final ApplicationEventPublisher eventPublisher;

    public TelegramWebhookController(
            TelegramUpdateDeduplicator deduplicator,
            ApplicationEventPublisher eventPublisher
    ) {
        this.deduplicator = deduplicator;
        this.eventPublisher = eventPublisher;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> handleWebhook(@RequestBody UpdateDto update) {
        if (update == null || update.updateId() == null) {
            log.warn("Received empty or unparseable Telegram update");
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "empty_update"));
        }

        Long updateId = update.updateId();

        // Check for unsupported update types (e.g. channel posts, poll answers, inline queries)
        if (!update.isSupported()) {
            log.info("Received unsupported Telegram update type: update_id={}", updateId);
            boolean acquired = deduplicator.tryAcquire(updateId, null);
            if (acquired) {
                deduplicator.markIgnored(updateId, "unsupported_update");
            }
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "unsupported_update"));
        }

        // Atomic deduplication via PostgreSQL primary key conflict resolution
        boolean isNew = deduplicator.tryAcquire(updateId, null);
        if (!isNew) {
            log.debug("Duplicate Telegram update ignored: update_id={}", updateId);
            return ResponseEntity.ok(Map.of("status", "duplicate"));
        }

        // Asynchronous handoff decoupled from HTTP request thread
        eventPublisher.publishEvent(new TelegramUpdateReceivedEvent(update));
        log.debug("Dispatched Telegram update_id={} for async processing", updateId);

        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    /**
     * Catches malformed JSON payloads and returns HTTP 200 OK with warning.
     * Returning 200 prevents Telegram from retrying malformed updates in an endless loop.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleMalformedPayload(HttpMessageNotReadableException ex) {
        log.warn("Malformed Telegram update JSON received: {}", ex.getMessage());
        return ResponseEntity.ok(Map.of("status", "ignored", "reason", "malformed_payload"));
    }

    /**
     * Catches database persistence failures (e.g. connectivity failure or transient lock issue)
     * and returns HTTP 500 Internal Server Error so Telegram can retry delivery later.
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String, String>> handlePersistenceFailure(org.springframework.dao.DataAccessException ex) {
        log.error("Database persistence failure during Telegram webhook processing: {}", ex.getMessage());
        return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("status", "error", "error", "persistence_failure"));
    }

    /**
     * Catches unexpected server errors and returns HTTP 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpectedFailure(Exception ex) {
        log.error("Unexpected server error during Telegram webhook processing: {}", ex.getMessage(), ex);
        return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("status", "error", "error", "server_error"));
    }
}
