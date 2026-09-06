package com.recallbot.concurrency;

import com.recallbot.ai.EmbeddingService;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupMembershipRepository;
import com.recallbot.core.group.GroupMembershipService;
import com.recallbot.core.group.GroupRepository;
import com.recallbot.core.group.GroupRole;
import com.recallbot.core.group.GroupService;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.core.message.MessageIngestionService;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserRepository;
import com.recallbot.core.user.UserService;
import com.recallbot.persistence.BasePostgresIntegrationTest;
import com.recallbot.telegram.TelegramUpdateDeduplicator;
import com.recallbot.telegram.dto.ChatDto;
import com.recallbot.telegram.dto.MessageDto;
import com.recallbot.telegram.dto.UpdateDto;
import com.recallbot.telegram.dto.UserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
class ConcurrentIngestionTest extends BasePostgresIntegrationTest {

    private static final int CONCURRENT_UPDATES = 50;
    private static final int THREAD_POOL_SIZE = 50;
    private static final int DIMENSION = 1536;

    @Autowired
    private MessageIngestionService messageIngestionService;

    @Autowired
    private TelegramUpdateDeduplicator deduplicator;

    @Autowired
    private GroupService groupService;

    @Autowired
    private UserService userService;

    @Autowired
    private GroupMembershipService groupMembershipService;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private GroupMembershipRepository membershipRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @MockBean
    private EmbeddingService embeddingService;

    private Long testChatId;
    private GroupEntity testGroup;

    @BeforeEach
    void setUp() {
        float[] vector = new float[DIMENSION];
        vector[0] = 0.5f;
        vector[1] = -0.2f;
        when(embeddingService.generateEmbedding(any())).thenReturn(vector);
        when(embeddingService.getDimension()).thenReturn(DIMENSION);
        when(embeddingService.getModelName()).thenReturn("openai/text-embedding-3-small");

        testChatId = -800000000L - (System.currentTimeMillis() % 10000000L);
        ChatDto chatDto = new ChatDto(testChatId, "supergroup", "Concurrency Test Group " + UUID.randomUUID(), null);
        testGroup = groupService.resolveGroup(chatDto);
    }

    @Test
    @DisplayName("Simulate 50 concurrent distinct updates for the same group without deadlocks, dropped messages, or duplicate rows")
    void testFiftyConcurrentDistinctUpdatesForSameGroup() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_UPDATES);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);

        long baseUpdateId = System.currentTimeMillis() * 1000L;
        long baseMessageId = 50000L;

        for (int i = 0; i < CONCURRENT_UPDATES; i++) {
            final int index = i;
            final long updateId = baseUpdateId + index;
            final long messageId = baseMessageId + index;
            final long userId = 200000L + (index % 5); // 5 distinct users sending concurrently

            Callable<Boolean> task = () -> {
                readyLatch.countDown();
                // Wait for all threads to be ready so they fire simultaneously
                if (!startLatch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timeout waiting for start latch");
                }

                UserDto userDto = new UserDto(userId, false, "user_" + userId, "User" + userId, null);
                ChatDto chatDto = new ChatDto(testChatId, "supergroup", testGroup.getTitle(), null);
                MessageDto messageDto = new MessageDto(
                        messageId,
                        userDto,
                        chatDto,
                        System.currentTimeMillis() / 1000L,
                        "Concurrent message content #" + index + " with random " + UUID.randomUUID(),
                        null,
                        null
                );
                UpdateDto updateDto = new UpdateDto(updateId, messageDto, null, null);

                // Simulate ingress deduplication followed by ingestion
                boolean acquired = deduplicator.tryAcquire(updateId, null);
                if (acquired) {
                    messageIngestionService.ingestUpdate(updateDto);
                    successCount.incrementAndGet();
                    return true;
                }
                return false;
            };

            futures.add(executor.submit(task));
        }

        // Release all threads simultaneously
        readyLatch.await(10, TimeUnit.SECONDS);
        startLatch.countDown();

        // Await all tasks completion
        for (Future<Boolean> future : futures) {
            Boolean result = future.get(30, TimeUnit.SECONDS);
            assertThat(result).isTrue();
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // Verify zero deadlocks and exactly 50 messages persisted
        assertThat(successCount.get()).isEqualTo(CONCURRENT_UPDATES);

        List<MessageEntity> messages = messageRepository.findByGroupId(testGroup.getId());
        assertThat(messages)
                .hasSize(CONCURRENT_UPDATES)
                .extracting(MessageEntity::getTelegramMessageId)
                .doesNotHaveDuplicates();

        // Verify all 5 users have valid memberships
        for (int u = 0; u < 5; u++) {
            long userId = 200000L + u;
            UserEntity user = userRepository.findByTelegramUserId(userId).orElseThrow();
            assertThat(membershipRepository.findByGroupIdAndUserId(testGroup.getId(), user.getId())).isPresent();
        }

        // Verify 50 telegram_updates rows recorded
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM telegram_updates WHERE update_id >= :minId AND update_id <= :maxId")
                .param("minId", baseUpdateId)
                .param("maxId", baseUpdateId + CONCURRENT_UPDATES - 1)
                .query(Integer.class)
                .single();
        assertThat(count).isEqualTo(CONCURRENT_UPDATES);
    }

    @Test
    @DisplayName("Simulate 50 concurrent duplicate updates for the EXACT SAME update ID and message ID; exactly 1 persists")
    void testFiftyConcurrentDuplicateUpdatesForSameMessageId() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_UPDATES);
        CountDownLatch startLatch = new CountDownLatch(1);

        final long duplicateUpdateId = System.currentTimeMillis() * 1000L + 88888L;
        final long duplicateMessageId = 999999L;
        final long userId = 77777L;

        List<Future<Boolean>> futures = new ArrayList<>();
        AtomicInteger acquiredCount = new AtomicInteger(0);

        for (int i = 0; i < CONCURRENT_UPDATES; i++) {
            Callable<Boolean> task = () -> {
                readyLatch.countDown();
                if (!startLatch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timeout waiting for start latch");
                }

                UserDto userDto = new UserDto(userId, false, "dup_user", "Dup", null);
                ChatDto chatDto = new ChatDto(testChatId, "supergroup", testGroup.getTitle(), null);
                MessageDto messageDto = new MessageDto(
                        duplicateMessageId,
                        userDto,
                        chatDto,
                        System.currentTimeMillis() / 1000L,
                        "Identical duplicate message content",
                        null,
                        null
                );
                UpdateDto updateDto = new UpdateDto(duplicateUpdateId, messageDto, null, null);

                boolean acquired = deduplicator.tryAcquire(duplicateUpdateId, null);
                if (acquired) {
                    acquiredCount.incrementAndGet();
                    messageIngestionService.ingestUpdate(updateDto);
                    return true;
                }
                return false;
            };

            futures.add(executor.submit(task));
        }

        readyLatch.await(10, TimeUnit.SECONDS);
        startLatch.countDown();

        int trueResults = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) {
                trueResults++;
            }
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // Exactly one thread acquired the update
        assertThat(acquiredCount.get()).isEqualTo(1);
        assertThat(trueResults).isEqualTo(1);

        // Verify exactly one message persisted in the database
        List<MessageEntity> messages = messageRepository.findByGroupId(testGroup.getId())
                .stream()
                .filter(m -> m.getTelegramMessageId().equals(duplicateMessageId))
                .toList();

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getContent()).isEqualTo("Identical duplicate message content");
    }

    @Test
    @DisplayName("Simulate 50 concurrent calls to ensureMembership for the same group and user; no constraint violations")
    void testConcurrentGroupMembershipResolution() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_UPDATES);
        CountDownLatch startLatch = new CountDownLatch(1);

        UserDto userDto = new UserDto(654321L, false, "concurrent_member", "Member", null);
        UserEntity user = userService.resolveUser(userDto);

        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_UPDATES; i++) {
            Callable<Boolean> task = () -> {
                readyLatch.countDown();
                if (!startLatch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timeout waiting for start latch");
                }
                groupMembershipService.ensureMembership(testGroup, user, GroupRole.MEMBER);
                return true;
            };
            futures.add(executor.submit(task));
        }

        readyLatch.await(10, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<Boolean> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS)).isTrue();
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // Assert exactly one membership record exists in database
        assertThat(membershipRepository.findByGroupIdAndUserId(testGroup.getId(), user.getId())).isPresent();

        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM group_memberships WHERE group_id = :gid AND user_id = :uid")
                .param("gid", testGroup.getId())
                .param("uid", user.getId())
                .query(Integer.class)
                .single();
        assertThat(count).isEqualTo(1);
    }
}
