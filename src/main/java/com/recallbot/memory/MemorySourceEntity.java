package com.recallbot.memory;

import com.recallbot.core.message.MessageEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "memory_sources")
public class MemorySourceEntity {

    @EmbeddedId
    private MemorySourceId id = new MemorySourceId();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("memoryId")
    @JoinColumn(name = "memory_id", nullable = false)
    private MemoryEntity memory;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("messageId")
    @JoinColumn(name = "message_id", nullable = false)
    private MessageEntity message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public MemorySourceEntity() {
    }

    public MemorySourceEntity(MemoryEntity memory, MessageEntity message) {
        this.memory = memory;
        this.message = message;
        if (memory != null && memory.getId() != null && message != null && message.getId() != null) {
            this.id = new MemorySourceId(memory.getId(), message.getId());
        }
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public MemorySourceId getId() {
        return id;
    }

    public void setId(MemorySourceId id) {
        this.id = id;
    }

    public MemoryEntity getMemory() {
        return memory;
    }

    public void setMemory(MemoryEntity memory) {
        this.memory = memory;
    }

    public MessageEntity getMessage() {
        return message;
    }

    public void setMessage(MessageEntity message) {
        this.message = message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MemorySourceEntity that = (MemorySourceEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
