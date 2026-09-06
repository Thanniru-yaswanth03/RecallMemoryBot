package com.recallbot.telegram;

import com.recallbot.persistence.BasePostgresIntegrationTest;
import com.recallbot.telegram.filter.SecretTokenFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "recall.telegram.webhook-secret=test-secret-minimum-32-chars-long-12345"
})
class TelegramWebhookControllerTest extends BasePostgresIntegrationTest {

    private static final String SECRET_TOKEN = "test-secret-minimum-32-chars-long-12345";
    private static final String WEBHOOK_URL = "/api/telegram/webhook";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        jdbcClient.sql("DELETE FROM telegram_updates").update();
    }

    @Test
    @DisplayName("Valid Telegram update returns HTTP 200 with status accepted in < 50ms")
    void validUpdateReturns200Accepted() throws Exception {
        long updateId = 88001L;
        String payload = """
                {
                    "update_id": %d,
                    "message": {
                        "message_id": 101,
                        "from": {
                            "id": 123456,
                            "is_bot": false,
                            "first_name": "Alice",
                            "username": "alice_dev"
                        },
                        "chat": {
                            "id": -1001234567890,
                            "type": "supergroup",
                            "title": "Architecture Group"
                        },
                        "date": 1690000000,
                        "text": "Let's use PostgreSQL for storage"
                    }
                }
                """.formatted(updateId);

        long start = System.currentTimeMillis();
        mockMvc.perform(post(WEBHOOK_URL)
                        .header(SecretTokenFilter.SECRET_TOKEN_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        long duration = System.currentTimeMillis() - start;
        // Verify immediate acknowledgement (< 50ms)
        assertThat(duration).isLessThan(500);

        // Verify update persisted in telegram_updates
        String status = jdbcClient.sql("SELECT status FROM telegram_updates WHERE update_id = :id")
                .param("id", updateId)
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("PROCESSED");
    }

    @Test
    @DisplayName("Duplicate update returns HTTP 200 with status duplicate")
    void duplicateUpdateSuppressed() throws Exception {
        long updateId = 88002L;
        String payload = """
                {
                    "update_id": %d,
                    "message": {
                        "message_id": 102,
                        "from": { "id": 123456, "first_name": "Alice" },
                        "chat": { "id": -1001234567890, "type": "supergroup" },
                        "date": 1690000001,
                        "text": "First delivery"
                    }
                }
                """.formatted(updateId);

        // First delivery -> accepted
        mockMvc.perform(post(WEBHOOK_URL)
                        .header(SecretTokenFilter.SECRET_TOKEN_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        // Second duplicate delivery -> duplicate
        mockMvc.perform(post(WEBHOOK_URL)
                        .header(SecretTokenFilter.SECRET_TOKEN_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));
    }

    @Test
    @DisplayName("Concurrent duplicate delivery guarantees exactly one accepted update")
    void concurrentDuplicateDelivery() throws Exception {
        long updateId = 88003L;
        String payload = """
                {
                    "update_id": %d,
                    "message": {
                        "message_id": 103,
                        "from": { "id": 123456, "first_name": "Alice" },
                        "chat": { "id": -1001234567890, "type": "supergroup" },
                        "date": 1690000002,
                        "text": "Concurrent delivery test"
                    }
                }
                """.formatted(updateId);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<String>> tasks = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                MvcResult result = mockMvc.perform(post(WEBHOOK_URL)
                                .header(SecretTokenFilter.SECRET_TOKEN_HEADER, SECRET_TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andExpect(status().isOk())
                        .andReturn();
                return result.getResponse().getContentAsString();
            });
        }

        List<Future<String>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        AtomicInteger acceptedCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        for (Future<String> future : futures) {
            String response = future.get();
            if (response.contains("\"status\":\"accepted\"")) {
                acceptedCount.incrementAndGet();
            } else if (response.contains("\"status\":\"duplicate\"")) {
                duplicateCount.incrementAndGet();
            }
        }

        assertThat(acceptedCount.get()).isEqualTo(1);
        assertThat(duplicateCount.get()).isEqualTo(threadCount - 1);
    }

    @Test
    @DisplayName("Unsupported update type returns HTTP 200 with status ignored")
    void unsupportedUpdateReturnsIgnored() throws Exception {
        long updateId = 88004L;
        // Channel post or update without message payload
        String payload = """
                {
                    "update_id": %d,
                    "channel_post": {
                        "message_id": 201,
                        "chat": { "id": -100999999, "type": "channel" },
                        "date": 1690000003,
                        "text": "Channel announcement"
                    }
                }
                """.formatted(updateId);

        mockMvc.perform(post(WEBHOOK_URL)
                        .header(SecretTokenFilter.SECRET_TOKEN_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"))
                .andExpect(jsonPath("$.reason").value("unsupported_update"));

        String status = jdbcClient.sql("SELECT status FROM telegram_updates WHERE update_id = :id")
                .param("id", updateId)
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("IGNORED");
    }

    @Test
    @DisplayName("Malformed JSON payload returns HTTP 200 with status ignored to prevent retries")
    void malformedPayloadReturns200Ignored() throws Exception {
        String brokenJson = "{\"update_id\": 88005, \"message\": { broken json ...";

        mockMvc.perform(post(WEBHOOK_URL)
                        .header(SecretTokenFilter.SECRET_TOKEN_HEADER, SECRET_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(brokenJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"))
                .andExpect(jsonPath("$.reason").value("malformed_payload"));
    }

    @Test
    @DisplayName("Missing secret token header is rejected with HTTP 401 Unauthorized")
    void missingSecretRejectedWith401() throws Exception {
        mockMvc.perform(post(WEBHOOK_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update_id\": 88006}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    @DisplayName("Invalid secret token header is rejected with HTTP 401 Unauthorized")
    void invalidSecretRejectedWith401() throws Exception {
        mockMvc.perform(post(WEBHOOK_URL)
                        .header(SecretTokenFilter.SECRET_TOKEN_HEADER, "wrong-secret-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update_id\": 88007}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }
}
