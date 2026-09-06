package com.recallbot.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TelegramWebhookErrorHandlingTest {

    private static final String WEBHOOK_URL = "/api/telegram/webhook";

    @Mock
    private TelegramUpdateDeduplicator deduplicator;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TelegramWebhookController controller = new TelegramWebhookController(deduplicator, eventPublisher);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Persistence failure returns HTTP 500 with persistence_failure error so Telegram can retry")
    void persistenceFailureReturns500() throws Exception {
        when(deduplicator.tryAcquire(anyLong(), any()))
                .thenThrow(new DataAccessResourceFailureException("PostgreSQL connection refused"));

        String payload = """
                {
                    "update_id": 99001,
                    "message": {
                        "message_id": 1,
                        "chat": { "id": -1001, "type": "group" },
                        "date": 1690000000,
                        "text": "Hello database down"
                    }
                }
                """;

        mockMvc.perform(post(WEBHOOK_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.error").value("persistence_failure"));
    }

    @Test
    @DisplayName("Unexpected server error returns HTTP 500 with server_error")
    void unexpectedErrorReturns500() throws Exception {
        when(deduplicator.tryAcquire(anyLong(), any())).thenReturn(true);
        doThrow(new RuntimeException("Task rejected from executor"))
                .when(eventPublisher).publishEvent(any());

        String payload = """
                {
                    "update_id": 99002,
                    "message": {
                        "message_id": 2,
                        "chat": { "id": -1001, "type": "group" },
                        "date": 1690000000,
                        "text": "Hello unexpected error"
                    }
                }
                """;

        mockMvc.perform(post(WEBHOOK_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.error").value("server_error"));
    }
}
