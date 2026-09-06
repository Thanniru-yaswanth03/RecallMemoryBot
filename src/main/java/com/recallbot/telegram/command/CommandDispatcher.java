package com.recallbot.telegram.command;

import com.recallbot.telegram.dto.MessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Dispatches Telegram bot commands to registered CommandHandler implementations.
 */
@Component
public class CommandDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CommandDispatcher.class);

    private final List<CommandHandler> handlers;

    public CommandDispatcher(List<CommandHandler> handlers) {
        this.handlers = handlers;
    }

    /**
     * Inspects the message and invokes the appropriate command handler if supported.
     *
     * @param message the Telegram message containing the command
     */
    public void dispatch(MessageDto message) {
        if (message == null) {
            return;
        }

        String content = message.extractContent();
        if (content == null || content.isBlank() || !content.stripLeading().startsWith("/")) {
            return;
        }

        String commandToken = extractCommandToken(content);
        log.debug("Dispatching command '{}' for message_id={}", commandToken, message.messageId());

        for (CommandHandler handler : handlers) {
            if (handler.canHandle(commandToken)) {
                try {
                    handler.handle(message);
                } catch (Exception e) {
                    log.error("Error executing handler for command '{}': {}", commandToken, e.getMessage(), e);
                }
                return;
            }
        }

        log.debug("No handler registered for command '{}'", commandToken);
    }

    private String extractCommandToken(String text) {
        String stripped = text.stripLeading();
        int spaceIdx = stripped.indexOf(' ');
        String fullCommand = spaceIdx != -1 ? stripped.substring(0, spaceIdx) : stripped;

        // Strip bot username suffix if present (e.g. "/ask@RecallMemoryBot" -> "/ask")
        int atIdx = fullCommand.indexOf('@');
        if (atIdx != -1) {
            return fullCommand.substring(0, atIdx).toLowerCase();
        }
        return fullCommand.toLowerCase();
    }
}
