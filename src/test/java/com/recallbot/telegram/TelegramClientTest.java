package com.recallbot.telegram;

import com.recallbot.config.properties.RecallProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TelegramClientTest {

    private static final String BOT_TOKEN = "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11";
    private MockRestServiceServer mockServer;
    private TelegramClient telegramClient;

    @BeforeEach
    void setUp() {
        RecallProperties properties = new RecallProperties(
                new RecallProperties.Telegram("webhook", "recall_bot", BOT_TOKEN, "secret", null),
                new RecallProperties.Ai("key", "chat", "embed", 1024, 30, 800, 0.2),
                new RecallProperties.Search(15, 60, 2500),
                new RecallProperties.RateLimit(3, 10)
        );

        RestClient.Builder restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        telegramClient = new TelegramClientImpl(properties, restClientBuilder);
    }

    @Test
    @DisplayName("Successfully send message via Telegram Bot API")
    void sendMessageSuccess() {
        mockServer.expect(requestTo("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().json("""
                        {
                            "chat_id": -1001234567890,
                            "text": "Hello world",
                            "parse_mode": "MarkdownV2",
                            "reply_to_message_id": 42
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                            "ok": true,
                            "result": {
                                "message_id": 100,
                                "date": 1690000000
                            }
                        }
                        """, MediaType.APPLICATION_JSON));

        boolean sent = telegramClient.sendMessage(-1001234567890L, "Hello world", "MarkdownV2", 42L);

        assertThat(sent).isTrue();
        mockServer.verify();
    }

    @Test
    @DisplayName("Handle HTTP 400 error from Telegram API gracefully without crashing")
    void sendMessageBadRequestHandledGracefully() {
        mockServer.expect(requestTo("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withBadRequest().body("""
                        {
                            "ok": false,
                            "error_code": 400,
                            "description": "Bad Request: chat not found"
                        }
                        """).contentType(MediaType.APPLICATION_JSON));

        boolean sent = telegramClient.sendMessage(-999999999L, "Test fail");

        assertThat(sent).isFalse();
        mockServer.verify();
    }

    @Test
    @DisplayName("Successfully send chat action typing via Telegram Bot API")
    void sendChatActionSuccess() {
        mockServer.expect(requestTo("https://api.telegram.org/bot" + BOT_TOKEN + "/sendChatAction"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                            "chat_id": -1001234567890,
                            "action": "typing"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                            "ok": true,
                            "result": true
                        }
                        """, MediaType.APPLICATION_JSON));

        boolean sent = telegramClient.sendChatAction(-1001234567890L, "typing");

        assertThat(sent).isTrue();
        mockServer.verify();
    }

    @Test
    @DisplayName("isChatAdmin returns true when Telegram API returns administrator status")
    void isChatAdminReturnsTrueForAdmin() {
        mockServer.expect(requestTo("https://api.telegram.org/bot" + BOT_TOKEN + "/getChatMember?chat_id=-1001234567890&user_id=12345"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "ok": true,
                            "result": {
                                "status": "administrator",
                                "user": { "id": 12345, "first_name": "Admin" }
                            }
                        }
                        """, MediaType.APPLICATION_JSON));

        boolean isAdmin = telegramClient.isChatAdmin(-1001234567890L, 12345L);

        assertThat(isAdmin).isTrue();
        mockServer.verify();
    }

    @Test
    @DisplayName("isChatAdmin returns true when Telegram API returns creator status")
    void isChatAdminReturnsTrueForCreator() {
        mockServer.expect(requestTo("https://api.telegram.org/bot" + BOT_TOKEN + "/getChatMember?chat_id=-1001234567890&user_id=12345"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "ok": true,
                            "result": {
                                "status": "creator",
                                "user": { "id": 12345, "first_name": "Owner" }
                            }
                        }
                        """, MediaType.APPLICATION_JSON));

        boolean isAdmin = telegramClient.isChatAdmin(-1001234567890L, 12345L);

        assertThat(isAdmin).isTrue();
        mockServer.verify();
    }

    @Test
    @DisplayName("isChatAdmin returns false when Telegram API returns member status")
    void isChatAdminReturnsFalseForMember() {
        mockServer.expect(requestTo("https://api.telegram.org/bot" + BOT_TOKEN + "/getChatMember?chat_id=-1001234567890&user_id=12345"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "ok": true,
                            "result": {
                                "status": "member",
                                "user": { "id": 12345, "first_name": "Regular" }
                            }
                        }
                        """, MediaType.APPLICATION_JSON));

        boolean isAdmin = telegramClient.isChatAdmin(-1001234567890L, 12345L);

        assertThat(isAdmin).isFalse();
        mockServer.verify();
    }
}
