package com.recallbot.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recallbot.ai.AIService;
import com.recallbot.ai.EmbeddingService;
import com.recallbot.config.properties.RecallProperties;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupRepository;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.core.message.MessageEmbeddingRepository;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserRepository;
import com.recallbot.memory.MemoryEntity;
import com.recallbot.memory.MemoryRepository;
import com.recallbot.persistence.BasePostgresIntegrationTest;
import com.recallbot.telegram.TelegramClient;
import com.recallbot.telegram.dto.ChatDto;
import com.recallbot.telegram.dto.MessageDto;
import com.recallbot.telegram.dto.UpdateDto;
import com.recallbot.telegram.dto.UserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "recall.telegram.webhook-secret=test_e2e_secret_token")
class TelegramFlowIT extends BasePostgresIntegrationTest {

    private static final int DIMENSION = 1536;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecallProperties properties;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageEmbeddingRepository messageEmbeddingRepository;

    @Autowired
    private MemoryRepository memoryRepository;

    @MockBean
    private TelegramClient telegramClient;

    @MockBean
    private AIService aiService;

    @MockBean
    private EmbeddingService embeddingService;

    private String secretToken;
    private Long uniqueChatId;

    @BeforeEach
    void setUp() {
        secretToken = properties.telegram() != null ? properties.telegram().webhookSecret() : "test_secret_token";

        when(telegramClient.sendMessage(anyLong(), anyString(), any(), any())).thenReturn(true);
        when(telegramClient.sendChatAction(anyLong(), anyString())).thenReturn(true);

        float[] vector = new float[DIMENSION];
        vector[0] = 0.5f;
        vector[1] = -0.3f;
        when(embeddingService.generateEmbedding(anyString())).thenReturn(vector);
        when(embeddingService.getDimension()).thenReturn(DIMENSION);
        when(embeddingService.getModelName()).thenReturn("openai/text-embedding-3-small");

        uniqueChatId = -950000000L - (System.currentTimeMillis() % 1000000L);
    }

    @Test
    @DisplayName("Complete webhook -> ingestion -> persistence -> embedding flow succeeds end-to-end")
    void testCompleteWebhookIngestionAndVectorizationFlow() throws Exception {
        long updateId = 100000L + (System.currentTimeMillis() % 100000L);
        long messageId = 2001L;
        long senderUserId = 555123L;

        UserDto userDto = new UserDto(senderUserId, false, "Alice", null, "alice_flow");
        ChatDto chatDto = new ChatDto(uniqueChatId, "supergroup", "Flow Group " + UUID.randomUUID(), null);
        MessageDto messageDto = new MessageDto(
                messageId, userDto, chatDto, System.currentTimeMillis() / 1000L,
                "We agreed that release v1.0 is set for October 15th.", null, null
        );
        UpdateDto updateDto = new UpdateDto(updateId, messageDto, null, null);

        // Send valid webhook
        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", secretToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        // Use Awaitility to verify asynchronous persistence in PostgreSQL
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            GroupEntity group = groupRepository.findByTelegramChatId(uniqueChatId).orElse(null);
            assertThat(group).isNotNull();

            UserEntity user = userRepository.findByTelegramUserId(senderUserId).orElse(null);
            assertThat(user).isNotNull();
            assertThat(user.getUsername()).isEqualTo("alice_flow");

            List<MessageEntity> messages = messageRepository.findByGroupId(group.getId());
            assertThat(messages).hasSize(1);
            MessageEntity savedMessage = messages.get(0);
            assertThat(savedMessage.getTelegramMessageId()).isEqualTo(messageId);
            assertThat(savedMessage.getContent()).isEqualTo("We agreed that release v1.0 is set for October 15th.");

            // Verify async vector embedding generation and storage in message_embeddings
            assertThat(messageEmbeddingRepository.findByMessageId(savedMessage.getId())).isPresent();
        });
    }

    @Test
    @DisplayName("Duplicate Telegram update ID is handled idempotently via webhook")
    void testWebhookUpdateDeduplicationIdempotency() throws Exception {
        long duplicateUpdateId = 888800L + (System.currentTimeMillis() % 10000L);
        long messageId = 3001L;

        UserDto user = new UserDto(666111L, false, "Bob", null, "bob_dup");
        ChatDto chat = new ChatDto(uniqueChatId, "supergroup", "Dedup Group " + UUID.randomUUID(), null);
        MessageDto message = new MessageDto(messageId, user, chat, System.currentTimeMillis() / 1000L, "First delivery", null, null);
        UpdateDto update = new UpdateDto(duplicateUpdateId, message, null, null);

        String json = objectMapper.writeValueAsString(update);

        // 1st delivery -> accepted
        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", secretToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        // 2nd delivery -> duplicate
        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", secretToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));

        // Wait to verify only 1 message exists in DB
        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            GroupEntity group = groupRepository.findByTelegramChatId(uniqueChatId).orElse(null);
            if (group != null) {
                List<MessageEntity> messages = messageRepository.findByGroupId(group.getId());
                assertThat(messages).hasSize(1);
            }
        });
    }

    @Test
    @DisplayName("Command /remember records explicit memory via webhook and confirms to chat")
    void testRememberCommandFlow() throws Exception {
        long updateId = 910000L + (System.currentTimeMillis() % 10000L);
        long messageId = 4001L;
        long userId = 777222L;

        UserDto user = new UserDto(userId, false, "Admin", null, "admin_user");
        ChatDto chat = new ChatDto(uniqueChatId, "supergroup", "Remember Group " + UUID.randomUUID(), null);
        MessageDto message = new MessageDto(
                messageId, user, chat, System.currentTimeMillis() / 1000L,
                "/remember Weekly standup is every Monday at 9:30 AM", null, null
        );
        UpdateDto update = new UpdateDto(updateId, message, null, null);

        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", secretToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            GroupEntity group = groupRepository.findByTelegramChatId(uniqueChatId).orElse(null);
            assertThat(group).isNotNull();

            List<MemoryEntity> memories = memoryRepository.findByGroupId(group.getId());
            assertThat(memories).hasSize(1);
            assertThat(memories.get(0).getContent()).isEqualTo("Weekly standup is every Monday at 9:30 AM");

            verify(telegramClient, atLeastOnce()).sendMessage(
                    eq(uniqueChatId),
                    contains("Weekly standup is every Monday at 9:30 AM"),
                    any(),
                    eq(messageId)
            );
        });
    }

    @Test
    @DisplayName("Command /ask performs semantic search, calls AI, validates citations, and replies")
    void testAskCommandFullFlow() throws Exception {
        // Step 1: Pre-populate conversation message in group
        long seedUpdateId = 111000L + (System.currentTimeMillis() % 10000L);
        long seedMsgId = 5001L;
        UserDto user = new UserDto(888333L, false, "Charlie", null, "charlie");
        ChatDto chat = new ChatDto(uniqueChatId, "supergroup", "Ask Group " + UUID.randomUUID(), null);
        MessageDto seedMsg = new MessageDto(
                seedMsgId, user, chat, System.currentTimeMillis() / 1000L,
                "Our production database is PostgreSQL 16 on Hetzner.", null, null
        );
        UpdateDto seedUpdate = new UpdateDto(seedUpdateId, seedMsg, null, null);

        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", secretToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(seedUpdate)))
                .andExpect(status().isOk());

        // Wait for seed message to be vectorized
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            GroupEntity group = groupRepository.findByTelegramChatId(uniqueChatId).orElse(null);
            assertThat(group).isNotNull();
            List<MessageEntity> msgs = messageRepository.findByGroupId(group.getId());
            assertThat(msgs).hasSize(1);
            assertThat(messageEmbeddingRepository.findByMessageId(msgs.get(0).getId())).isPresent();
        });

        // Step 2: Configure AI mock response
        when(aiService.generateGroundedAnswer(anyString(), anyString()))
                .thenReturn("The team runs PostgreSQL 16 on Hetzner [Msg #" + seedMsgId + "].");

        // Step 3: Trigger /ask command
        long askUpdateId = 112000L + (System.currentTimeMillis() % 10000L);
        long askMsgId = 5002L;
        MessageDto askMsg = new MessageDto(
                askMsgId, user, chat, System.currentTimeMillis() / 1000L,
                "/ask What database and hosting are we using?", null, null
        );
        UpdateDto askUpdate = new UpdateDto(askUpdateId, askMsg, null, null);

        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", secretToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(askUpdate)))
                .andExpect(status().isOk());

        // Step 4: Verify grounded answer is returned via TelegramClient
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            verify(telegramClient).sendChatAction(eq(uniqueChatId), eq("typing"));
            verify(telegramClient).sendMessage(
                    eq(uniqueChatId),
                    contains("PostgreSQL 16 on Hetzner"),
                    isNull(),
                    eq(askMsgId)
            );
        });
    }

    @Test
    @DisplayName("Edge Case: Unicode characters, Cyrillic, Chinese, Arabic, and multi-byte emojis persist accurately")
    void testEdgeCaseUnicodeAndEmojis() throws Exception {
        long updateId = 120000L + (System.currentTimeMillis() % 10000L);
        long messageId = 6001L;

        String unicodeContent = "🚀 Project launch! 💡 Ideas: Привет мир | 你好世界 | مرحبا بك | 👨‍👩‍👧‍👦 Family emoji & accents: café, naïve, résumé 🎉🔥";

        UserDto user = new UserDto(999444L, false, "Тест", null, "unicode_tester");
        ChatDto chat = new ChatDto(uniqueChatId, "supergroup", "Unicode Group " + UUID.randomUUID(), null);
        MessageDto message = new MessageDto(messageId, user, chat, System.currentTimeMillis() / 1000L, unicodeContent, null, null);
        UpdateDto update = new UpdateDto(updateId, message, null, null);

        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", secretToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            GroupEntity group = groupRepository.findByTelegramChatId(uniqueChatId).orElse(null);
            assertThat(group).isNotNull();

            List<MessageEntity> msgs = messageRepository.findByGroupId(group.getId());
            assertThat(msgs).hasSize(1);
            assertThat(msgs.get(0).getContent()).isEqualTo(unicodeContent);
        });
    }

    @Test
    @DisplayName("Edge Case: Very long message (4000 characters) persists without error")
    void testEdgeCaseVeryLongMessage() throws Exception {
        long updateId = 130000L + (System.currentTimeMillis() % 10000L);
        long messageId = 7001L;

        String longContent = "A".repeat(4000);

        UserDto user = new UserDto(999555L, false, "Long", null, "long_user");
        ChatDto chat = new ChatDto(uniqueChatId, "supergroup", "Long Msg Group " + UUID.randomUUID(), null);
        MessageDto message = new MessageDto(messageId, user, chat, System.currentTimeMillis() / 1000L, longContent, null, null);
        UpdateDto update = new UpdateDto(updateId, message, null, null);

        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", secretToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            GroupEntity group = groupRepository.findByTelegramChatId(uniqueChatId).orElse(null);
            assertThat(group).isNotNull();

            List<MessageEntity> msgs = messageRepository.findByGroupId(group.getId());
            assertThat(msgs).hasSize(1);
            assertThat(msgs.get(0).getContent()).hasSize(4000);
        });
    }

    @Test
    @DisplayName("Edge Case: Empty or blank /ask command returns usage guidance")
    void testEdgeCaseEmptyQuestionOnAsk() throws Exception {
        long updateId = 140000L + (System.currentTimeMillis() % 10000L);
        long messageId = 8001L;

        UserDto user = new UserDto(999666L, false, "Empty", null, "empty_asker");
        ChatDto chat = new ChatDto(uniqueChatId, "supergroup", "Empty Ask Group " + UUID.randomUUID(), null);
        MessageDto message = new MessageDto(messageId, user, chat, System.currentTimeMillis() / 1000L, "/ask    ", null, null);
        UpdateDto update = new UpdateDto(updateId, message, null, null);

        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", secretToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            verify(telegramClient).sendMessage(
                    eq(uniqueChatId),
                    contains("Please provide a question after /ask"),
                    isNull(),
                    eq(messageId)
            );
        });
    }

    @Test
    @DisplayName("Edge Case: Zero retrieval hits returns deterministic fallback message")
    void testEdgeCaseZeroRetrievalResults() throws Exception {
        long updateId = 150000L + (System.currentTimeMillis() % 10000L);
        long messageId = 9001L;

        UserDto user = new UserDto(999777L, false, "Zero", null, "zero_asker");
        ChatDto chat = new ChatDto(uniqueChatId, "supergroup", "Zero Hits Group " + UUID.randomUUID(), null);
        MessageDto message = new MessageDto(
                messageId, user, chat, System.currentTimeMillis() / 1000L,
                "/ask What is the secret spaceship hyperdrive formula?", null, null
        );
        UpdateDto update = new UpdateDto(updateId, message, null, null);

        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", secretToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            verify(telegramClient).sendMessage(
                    eq(uniqueChatId),
                    eq("I don't have enough conversation history in this group to answer that question."),
                    isNull(),
                    eq(messageId)
            );
        });
    }

    @Test
    @DisplayName("Edge Case: Malformed JSON payload returns HTTP 200 with malformed_payload status")
    void testMalformedJsonWebhookHandling() throws Exception {
        String invalidJson = "{ \"update_id\": 12345, \"message\": { incomplete json ...";

        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", secretToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"))
                .andExpect(jsonPath("$.reason").value("malformed_payload"));
    }

    @Test
    @DisplayName("Edge Case: Unsupported update type returns HTTP 200 with unsupported_update reason")
    void testUnsupportedUpdateTypeHandling() throws Exception {
        // Update without effective message (e.g. channel_post or poll)
        String unsupportedJson = "{ \"update_id\": 999888777 }";

        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", secretToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unsupportedJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"))
                .andExpect(jsonPath("$.reason").value("unsupported_update"));
    }

    @Test
    @DisplayName("Security: Webhook rejects request with missing or invalid secret token (HTTP 401)")
    void testWebhookAuthenticationSecurity() throws Exception {
        String validPayload = "{ \"update_id\": 111111 }";

        // Missing header
        mockMvc.perform(post("/api/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload))
                .andExpect(status().isUnauthorized());

        // Invalid token
        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", "wrong_secret_token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload))
                .andExpect(status().isUnauthorized());
    }
}
