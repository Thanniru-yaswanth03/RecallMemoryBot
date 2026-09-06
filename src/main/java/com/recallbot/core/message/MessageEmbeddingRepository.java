package com.recallbot.core.message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface MessageEmbeddingRepository extends JpaRepository<MessageEmbeddingEntity, Long> {

    Optional<MessageEmbeddingEntity> findByMessageId(Long messageId);

    void deleteByMessageId(Long messageId);

    long countByGroupId(Long groupId);
}
