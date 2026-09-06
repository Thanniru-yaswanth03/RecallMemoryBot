package com.recallbot.telegram.command;

import com.recallbot.telegram.dto.MessageDto;

/**
 * Strategy interface for handling Telegram bot commands.
 */
public interface CommandHandler {

    /**
     * Determines whether this handler supports the given command token (e.g. "/ask").
     *
     * @param command lowercase command token without bot username (e.g. "/ask")
     * @return true if this handler can process the command
     */
    boolean canHandle(String command);

    /**
     * Executes the command logic for the given Telegram message.
     *
     * @param message the incoming Telegram message
     */
    void handle(MessageDto message);
}
