package com.recallbot.telegram;

import com.recallbot.config.properties.RecallProperties;
import com.recallbot.telegram.dto.SendMessageRequest;
import com.recallbot.telegram.dto.TelegramApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Implementation of TelegramClient backed by Spring 6 RestClient.
 * Targets the official Telegram Bot API without leaking tokens into logs.
 */
@Component
public class TelegramClientImpl implements TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClientImpl.class);
    private static final String DEFAULT_TELEGRAM_BASE_URL = "https://api.telegram.org";

    private final RestClient restClient;
    private final String botToken;

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramClientImpl(RecallProperties properties, RestClient.Builder restClientBuilder) {
        String token = (properties != null && properties.telegram() != null)
                ? properties.telegram().botToken()
                : null;
        this.botToken = (token != null) ? token.trim() : null;
        String baseUrl = DEFAULT_TELEGRAM_BASE_URL + "/bot" + (this.botToken != null ? this.botToken : "");
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    // Package-private constructor for testing with custom RestClient
    TelegramClientImpl(RestClient restClient) {
        this.restClient = restClient;
        this.botToken = null;
    }

    @Override
    public boolean sendMessage(Long chatId, String text) {
        return sendMessage(chatId, text, null, null);
    }

    @Override
    public boolean sendMessage(Long chatId, String text, String parseMode, Long replyToMessageId) {
        if (chatId == null || text == null || text.isBlank()) {
            log.warn("Cannot send Telegram message: chatId or text is null/blank");
            return false;
        }

        if (botToken != null && ("placeholder_token".equals(botToken) || botToken.isBlank())) {
            log.error("Cannot send Telegram message to chatId={}: bot token is not configured or placeholder", chatId);
            return false;
        }

        SendMessageRequest request = new SendMessageRequest(chatId, text, parseMode, replyToMessageId);

        try {
            TelegramApiResponse<Object> response = restClient.post()
                    .uri("/sendMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response != null && response.ok()) {
                log.debug("Successfully sent message to chatId={}", chatId);
                return true;
            } else {
                String desc = response != null ? response.description() : "empty response";
                log.warn("Telegram API returned non-ok for chatId={}: {}", chatId, desc);
                return false;
            }
        } catch (RestClientResponseException e) {
            if (replyToMessageId != null && e.getResponseBodyAsString().contains("message to be replied not found")) {
                log.warn("Replied message {} not found in Telegram, retrying delivery without reply_to_message_id", replyToMessageId);
                return sendMessage(chatId, text, parseMode, null);
            }
            log.error("Telegram API HTTP error {}: response={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.error("Failed to deliver message to chatId={}: {}", chatId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean sendChatAction(Long chatId, String action) {
        if (chatId == null || action == null || action.isBlank()) {
            return false;
        }

        if (botToken != null && ("placeholder_token".equals(botToken) || botToken.isBlank())) {
            log.warn("Cannot send chat action '{}' to chatId={}: bot token is not configured or placeholder", action, chatId);
            return false;
        }

        try {
            TelegramApiResponse<Boolean> response = restClient.post()
                    .uri("/sendChatAction")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", chatId, "action", action))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            return response != null && response.ok();
        } catch (Exception e) {
            log.warn("Failed to send chat action '{}' to chatId={}: {}", action, chatId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isChatAdmin(Long chatId, Long telegramUserId) {
        if (chatId == null || telegramUserId == null) {
            return false;
        }

        if (botToken != null && ("placeholder_token".equals(botToken) || botToken.isBlank())) {
            log.warn("Cannot check chat admin for chatId={}, userId={}: bot token is not configured or placeholder",
                    chatId, telegramUserId);
            return false;
        }

        try {
            TelegramApiResponse<Map<String, Object>> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/getChatMember")
                            .queryParam("chat_id", chatId)
                            .queryParam("user_id", telegramUserId)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response != null && response.ok() && response.result() != null) {
                Object statusObj = response.result().get("status");
                if (statusObj instanceof String status) {
                    boolean isAdmin = "administrator".equalsIgnoreCase(status) || "creator".equalsIgnoreCase(status);
                    log.debug("Chat member status for chatId={}, userId={}: status={}, isAdmin={}",
                            chatId, telegramUserId, status, isAdmin);
                    return isAdmin;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to check chat admin status for chatId={}, userId={}: {}",
                    chatId, telegramUserId, e.getMessage());
            return false;
        }
    }
}
