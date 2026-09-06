package com.recallbot.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class MemorySourceId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "memory_id")
    private Long memoryId;

    @Column(name = "message_id")
    private Long messageId;

    public MemorySourceId() {
    }

    public MemorySourceId(Long memoryId, Long messageId) {
        this.memoryId = memoryId;
        this.messageId = messageId;
    }

    public Long getMemoryId() {
        return memoryId;
    }

    public void setMemoryId(Long memoryId) {
        this.memoryId = memoryId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MemorySourceId that = (MemorySourceId) o;
        return Objects.equals(memoryId, that.memoryId) && Objects.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(memoryId, messageId);
    }
}
