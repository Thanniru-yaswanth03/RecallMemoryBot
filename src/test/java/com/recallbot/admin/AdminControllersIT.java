package com.recallbot.admin;

import com.recallbot.admin.activity.AdminActivityBuffer;
import com.recallbot.admin.activity.BotActivityEvent;
import com.recallbot.admin.security.AdminAuthFilter;
import com.recallbot.admin.security.AdminSession;
import com.recallbot.admin.security.AdminSessionManager;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipEntity;
import com.recallbot.core.group.GroupMembershipRepository;
import com.recallbot.core.group.GroupRepository;
import com.recallbot.core.group.GroupRole;
import com.recallbot.core.message.MessageEmbeddingEntity;
import com.recallbot.core.message.MessageEmbeddingRepository;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.core.message.MessageType;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserRepository;
import com.recallbot.memory.MemoryEntity;
import com.recallbot.memory.MemoryRepository;
import com.recallbot.memory.MemorySourceEntity;
import com.recallbot.memory.MemorySourceRepository;
import com.recallbot.memory.MemoryType;
import com.recallbot.persistence.BasePostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdminControllersIT extends BasePostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminSessionManager sessionManager;

    @Autowired
    private AdminActivityBuffer activityBuffer;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GroupMembershipRepository groupMembershipRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

    @Autowired
    private MessageEmbeddingRepository messageEmbeddingRepository;

    @Autowired
    private MemoryRepository memoryRepository;

    @Autowired
    private MemorySourceRepository memorySourceRepository;

    private String validToken;
    private GroupEntity testGroup;
    private UserEntity testUser;
    private MessageEntity testMessage;
    private MemoryEntity testMemory;

    @BeforeEach
    void setUp() {
        AdminSession session = sessionManager.createSession("admin", Duration.ofHours(1));
        validToken = session.getToken();

        // Seed test data with unique chat/user IDs
        long uniqueSuffix = System.currentTimeMillis();
        testGroup = new GroupEntity();
        testGroup.setTelegramChatId(-1008800000000L - (uniqueSuffix % 100000));
        testGroup.setTitle("Admin Test Group " + uniqueSuffix);
        testGroup.setActive(true);
        testGroup = groupRepository.save(testGroup);

        testUser = new UserEntity();
        testUser.setTelegramUserId(9900000L + (uniqueSuffix % 100000));
        testUser.setFirstName("Alice");
        testUser.setUsername("alice_admin_test");
        testUser = userRepository.save(testUser);

        GroupMembershipEntity membership = new GroupMembershipEntity(testGroup, testUser, GroupRole.ADMIN);
        groupMembershipRepository.save(membership);

        testMessage = new MessageEntity();
        testMessage.setGroup(testGroup);
        testMessage.setUser(testUser);
        testMessage.setTelegramMessageId(101L);
        testMessage.setContent("Critical decision: We must deploy to production <script>alert('xss')</script>");
        testMessage.setMessageType(MessageType.TEXT);
        testMessage.setSentAt(Instant.now());
        testMessage = messageRepository.save(testMessage);

        float[] sampleVec = new float[1536];
        sampleVec[0] = 0.5f;
        com.pgvector.PGvector pgVector = new com.pgvector.PGvector(sampleVec);
        jdbcClient.sql("""
                INSERT INTO message_embeddings (message_id, group_id, embedding, model_name, created_at)
                VALUES (?, ?, ?::vector, 'openai/text-embedding-3-small', CURRENT_TIMESTAMP)
                """)
                .param(testMessage.getId())
                .param(testGroup.getId())
                .param(pgVector)
                .update();

        testMemory = new MemoryEntity();
        testMemory.setGroup(testGroup);
        testMemory.setMemoryType(MemoryType.DECISION);
        testMemory.setContent("Deploy to production using pgvector.");
        testMemory.setConfidence(new BigDecimal("0.98"));
        testMemory.setModelName("openai/text-embedding-3-small");
        testMemory.setEmbedding(sampleVec);
        testMemory = memoryRepository.save(testMemory);

        MemorySourceEntity source = new MemorySourceEntity(testMemory, testMessage);
        memorySourceRepository.save(source);

        activityBuffer.recordEvent(new BotActivityEvent(
                "test-evt-1",
                Instant.now(),
                "COMMAND_ASK",
                testGroup.getId(),
                testGroup.getTitle(),
                "SUCCESS",
                120,
                "Retrieved 5 candidates"
        ));
    }

    @Test
    @DisplayName("Unauthenticated request to admin API returns 401 Unauthorized")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    @DisplayName("GET /api/admin/dashboard returns accurate statistics and health")
    void dashboardReturnsAccurateStats() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.totalGroups", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.stats.totalUsers", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.stats.totalMessages", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.stats.totalMemories", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.stats.totalEmbeddings", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.health.appStatus").value("UP"))
                .andExpect(jsonPath("$.health.database").value("CONNECTED"))
                .andExpect(jsonPath("$.health.pgvector").value("AVAILABLE"))
                .andExpect(jsonPath("$.aiConfig.embeddingDimension").value(1536))
                // Ensure no secrets leaked
                .andExpect(content().string(not(containsString("placeholder_secret"))))
                .andExpect(content().string(not(containsString("recall_pass"))));
    }

    @Test
    @DisplayName("GET /api/admin/groups returns paginated groups")
    void getGroupsSuccess() throws Exception {
        mockMvc.perform(get("/api/admin/groups?search=" + testGroup.getTitle())
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.content[?(@.id == " + testGroup.getId() + ")].title")
                        .value(hasItem(testGroup.getTitle())));
    }

    @Test
    @DisplayName("GET /api/admin/groups/{id} returns full group details")
    void getGroupDetailsSuccess() throws Exception {
        mockMvc.perform(get("/api/admin/groups/" + testGroup.getId())
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group.id").value(testGroup.getId()))
                .andExpect(jsonPath("$.group.title").value(testGroup.getTitle()))
                .andExpect(jsonPath("$.embeddingCount", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.members", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.recentMessages", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.recentMemories", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("GET /api/admin/groups/{id} for non-existent group returns 404")
    void getGroupDetailsNotFound() throws Exception {
        mockMvc.perform(get("/api/admin/groups/999999999")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/admin/groups/{id}/members returns paginated members")
    void getGroupMembersSuccess() throws Exception {
        mockMvc.perform(get("/api/admin/groups/" + testGroup.getId() + "/members")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.content[0].username").value("alice_admin_test"));
    }

    @Test
    @DisplayName("GET /api/admin/messages returns paginated messages with XSS sanitization")
    void getMessagesWithXssSanitization() throws Exception {
        mockMvc.perform(get("/api/admin/messages?groupId=" + testGroup.getId())
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.content[0].hasEmbedding").value(true))
                // Verify script tag is sanitized to HTML entities
                .andExpect(jsonPath("$.content[0].contentSnippet", containsString("&lt;script&gt;alert(&#x27;xss&#x27;)&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert('xss')</script>"))));
    }

    @Test
    @DisplayName("GET /api/admin/memories returns memories with provenance without leaking raw vector floats")
    void getMemoriesProvenanceAndVectorSafety() throws Exception {
        mockMvc.perform(get("/api/admin/memories?groupId=" + testGroup.getId())
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.content[0].content").value("Deploy to production using pgvector."))
                .andExpect(jsonPath("$.content[0].memoryType").value("DECISION"))
                .andExpect(jsonPath("$.content[0].sources", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.content[0].sources[0].telegramMessageId").value(101))
                // Verify embedding vector floats are NOT exposed
                .andExpect(jsonPath("$.content[0].embedding").doesNotExist())
                .andExpect(content().string(not(containsString("[0.5,"))));
    }

    @Test
    @DisplayName("GET /api/admin/activity returns recent operational events")
    void getActivitySuccess() throws Exception {
        mockMvc.perform(get("/api/admin/activity?limit=10")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[?(@.id == 'test-evt-1')].eventType").value(hasItem("COMMAND_ASK")));
    }

    @Test
    @DisplayName("GET /api/admin/system/health returns complete diagnostics")
    void getSystemHealthSuccess() throws Exception {
        mockMvc.perform(get("/api/admin/system/health")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appStatus").value("UP"))
                .andExpect(jsonPath("$.dbStatus").value("CONNECTED"))
                .andExpect(jsonPath("$.pgvectorStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.aiProvider").value("OpenRouter"))
                .andExpect(jsonPath("$.totalMessages", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.totalEmbedded", greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("Multiple adversarial XSS payloads are safely escaped in messages API")
    void multipleXssPayloadsAreSafelyEscaped() throws Exception {
        // Seed multiple adversarial payloads
        String[] payloads = {
                "<img src=x onerror=alert(1)>",
                "\"><script>alert(1)</script>",
                "<svg/onload=alert(1)>",
                "' onfocus='alert(1)"
        };

        for (int i = 0; i < payloads.length; i++) {
            MessageEntity xssMsg = new MessageEntity();
            xssMsg.setGroup(testGroup);
            xssMsg.setUser(testUser);
            xssMsg.setTelegramMessageId(2000L + i);
            xssMsg.setContent(payloads[i]);
            xssMsg.setMessageType(MessageType.TEXT);
            xssMsg.setSentAt(Instant.now());
            messageRepository.save(xssMsg);
        }

        mockMvc.perform(get("/api/admin/messages?groupId=" + testGroup.getId() + "&size=50")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<img src=x onerror=alert(1)>"))))
                .andExpect(content().string(not(containsString("\"><script>alert(1)</script>"))))
                .andExpect(content().string(not(containsString("<svg/onload=alert(1)>"))))
                .andExpect(jsonPath("$.content[?(@.telegramMessageId == 2000)].contentSnippet")
                        .value(hasItem(containsString("&lt;img src=x onerror=alert(1)&gt;"))))
                .andExpect(jsonPath("$.content[?(@.telegramMessageId == 2001)].contentSnippet")
                        .value(hasItem(containsString("&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;"))));
    }

    @Test
    @DisplayName("Non-existent resources return clean empty pages or 404 rather than 500 error")
    void nonExistentResourcesReturnCleanResponses() throws Exception {
        // Query messages for non-existent group
        mockMvc.perform(get("/api/admin/messages?groupId=999999999")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));

        // Query memories for non-existent group
        mockMvc.perform(get("/api/admin/memories?groupId=999999999")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("Invalid or malformed IDs return 400 Bad Request")
    void invalidIdsReturn400BadRequest() throws Exception {
        mockMvc.perform(get("/api/admin/groups/-5")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/admin/groups/0")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/admin/groups/invalid-id")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Client-requested page sizes exceeding maximum are capped server-side at 100")
    void paginationLimitsAreEnforcedServerSide() throws Exception {
        mockMvc.perform(get("/api/admin/messages?size=500")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        mockMvc.perform(get("/api/admin/groups?size=999")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    @DisplayName("Case-varied path and unauthenticated browser requests are safely protected")
    void caseVariedPathAndBrowserRequestsAreProtected() throws Exception {
        mockMvc.perform(get("/API/ADMIN/DASHBOARD"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));

        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login.html"));
    }

    @Test
    @DisplayName("Admin view routes: /admin, /admin/, /admin/login redirect or forward correctly")
    void adminViewRoutesVerification() throws Exception {
        // Unauthenticated /admin -> 302 redirect to /admin/login.html
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login.html"));

        // Unauthenticated /admin/ -> 302 redirect to /admin/login.html
        mockMvc.perform(get("/admin/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login.html"));

        // Public /admin/login -> forwards to /admin/login.html
        mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/admin/login.html"));

        // Public /admin/login/ -> forwards to /admin/login.html
        mockMvc.perform(get("/admin/login/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/admin/login.html"));

        // Authenticated /admin -> forwards to /admin/index.html
        mockMvc.perform(get("/admin")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/admin/index.html"));

        // Authenticated /admin/ -> forwards to /admin/index.html
        mockMvc.perform(get("/admin/")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/admin/index.html"));
    }
}
