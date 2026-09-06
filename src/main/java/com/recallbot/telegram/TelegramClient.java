package com.recallbot.telegram;

/**
 * Outbound client for interacting with the Telegram Bot API.
 */
public interface TelegramClient {

    /**
     * Sends a plain text message to a chat.
     */
    boolean sendMessage(Long chatId, String text);

    /**
     * Sends a formatted message with parse mode and optional reply target.
     */
    boolean sendMessage(Long chatId, String text, String parseMode, Long replyToMessageId);

    /**
     * Sends a chat action (e.g. 'typing').
     */
    boolean sendChatAction(Long chatId, String action);

    /**
     * Checks if a user is an administrator or creator in the specified Telegram chat.
     *
     * @param chatId         the Telegram chat ID
     * @param telegramUserId the Telegram user ID
     * @return true if the user has administrator or creator status; false otherwise
     */
    boolean isChatAdmin(Long chatId, Long telegramUserId);
}
