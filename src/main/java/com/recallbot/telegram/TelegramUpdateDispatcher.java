package com.recallbot.telegram;

import com.recallbot.config.AsyncConfig;
import com.recallbot.telegram.event.TelegramUpdateReceivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Background consumer that handles Telegram updates asynchronously,
 * completely decoupled from the webhook HTTP request thread.
 */
@Component
public class TelegramUpdateDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateDispatcher.class);

    private final com.recallbot.core.message.MessageIngestionService messageIngestionService;
    private final com.recallbot.telegram.command.CommandDispatcher commandDispatcher;

    public TelegramUpdateDispatcher(
            com.recallbot.core.message.MessageIngestionService messageIngestionService,
            com.recallbot.telegram.command.CommandDispatcher commandDispatcher
    ) {
        this.messageIngestionService = messageIngestionService;
        this.commandDispatcher = commandDispatcher;
    }

    @Async(AsyncConfig.TELEGRAM_TASK_EXECUTOR)
    @EventListener
    public void onUpdateReceived(TelegramUpdateReceivedEvent event) {
        if (event == null || event.update() == null) {
            return;
        }

        Long updateId = event.update().updateId();
        log.debug("Asynchronous worker received update_id={} on thread={}",
                updateId, Thread.currentThread().getName());

        try {
            var update = event.update();
            var message = update.effectiveMessage();
            String content = message != null ? message.extractContent() : null;
            if (content != null && content.stripLeading().startsWith("/")) {
                commandDispatcher.dispatch(message);
            } else {
                messageIngestionService.ingestUpdate(update);
            }
        } catch (Exception e) {
            log.error("Unhandled error during background processing of update_id={}: {}",
                    updateId, e.getMessage(), e);
        }
    }
}
