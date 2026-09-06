package com.recallbot.persistence;

import com.recallbot.ai.EmbeddingService;
import com.recallbot.core.group.GroupEntity;
import com.recallbot.core.group.GroupRepository;
import com.recallbot.core.message.EmbeddingReconciler;
import com.recallbot.core.message.MessageEmbeddingRepository;
import com.recallbot.core.message.MessageEntity;
import com.recallbot.core.message.MessageRepository;
import com.recallbot.core.user.UserEntity;
import com.recallbot.core.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@SpringBootTest
class EmbeddingReconcilerIT extends BasePostgresIntegrationTest {

    private static final int DIMENSION = 1536;
    private static final String MODEL = "openai/text-embedding-3-small";

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageEmbeddingRepository messageEmbeddingRepository;

    @Autowired
    private EmbeddingReconciler embeddingReconciler;

    @MockBean
    private EmbeddingService embeddingService;

    private float[] createTestVector(float base) {
        float[] v = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            v[i] = base + (float) i / 10000.0f;
        }
        return v;
    }

    @Test
    @Transactional
    @DisplayName("Reconciler detects un-embedded messages and backfills them in batches")
    void reconcilesMissingEmbeddings() {
        GroupEntity group = groupRepository.save(new GroupEntity(-10099001122L, "Reconciler Test Group"));
        UserEntity user = userRepository.save(new UserEntity(77665544L, "bob", "Bob", null));

        MessageEntity m1 = messageRepository.save(new MessageEntity(group, user, 701L, "Message one", Instant.now()));
        MessageEntity m2 = messageRepository.save(new MessageEntity(group, user, 702L, "Message two", Instant.now()));
        MessageEntity m3 = messageRepository.save(new MessageEntity(group, user, 703L, "Message three", Instant.now()));

        when(embeddingService.getModelName()).thenReturn(MODEL);
        when(embeddingService.getDimension()).thenReturn(DIMENSION);
        when(embeddingService.generateEmbeddings(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(t -> createTestVector(0.1f)).toList();
        });

        int reconciled = embeddingReconciler.reconcileBatch(10);
        assertThat(reconciled).isGreaterThanOrEqualTo(3);

        assertThat(messageEmbeddingRepository.findByMessageId(m1.getId())).isPresent();
        assertThat(messageEmbeddingRepository.findByMessageId(m2.getId())).isPresent();
        assertThat(messageEmbeddingRepository.findByMessageId(m3.getId())).isPresent();

        // Second pass should have nothing left to reconcile
        int secondPass = embeddingReconciler.reconcileBatch(10);
        assertThat(secondPass).isEqualTo(0);
    }
}
