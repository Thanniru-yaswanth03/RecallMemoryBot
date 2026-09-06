package com.recallbot.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MemorySourceRepository extends JpaRepository<MemorySourceEntity, MemorySourceId> {

    List<MemorySourceEntity> findByMemoryId(Long memoryId);

    List<MemorySourceEntity> findByMessageId(Long messageId);
}
