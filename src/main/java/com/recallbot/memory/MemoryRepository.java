package com.recallbot.memory;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MemoryRepository extends JpaRepository<MemoryEntity, Long> {

    List<MemoryEntity> findByGroupId(Long groupId);

    List<MemoryEntity> findByGroupIdAndMemoryType(Long groupId, MemoryType memoryType);

    long countByGroupId(Long groupId);

    Page<MemoryEntity> findByGroupId(Long groupId, Pageable pageable);

    List<MemoryEntity> findTop10ByGroupIdOrderByCreatedAtDesc(Long groupId);

    @Query("""
        SELECT m FROM MemoryEntity m
        WHERE (:groupId IS NULL OR m.group.id = :groupId)
          AND (:memoryType IS NULL OR m.memoryType = :memoryType)
        ORDER BY m.createdAt DESC
    """)
    Page<MemoryEntity> findFilteredMemories(
            @Param("groupId") Long groupId,
            @Param("memoryType") MemoryType memoryType,
            Pageable pageable
    );

    @Query("""
        SELECT m FROM MemoryEntity m
        WHERE (:groupId IS NULL OR m.group.id = :groupId)
          AND (:memoryType IS NULL OR m.memoryType = :memoryType)
          AND LOWER(m.content) LIKE :queryPattern
        ORDER BY m.createdAt DESC
    """)
    Page<MemoryEntity> searchMemories(
            @Param("groupId") Long groupId,
            @Param("memoryType") MemoryType memoryType,
            @Param("queryPattern") String queryPattern,
            Pageable pageable
    );
}
