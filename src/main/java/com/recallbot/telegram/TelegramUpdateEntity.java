package com.recallbot.telegram;

import com.recallbot.core.group.GroupEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "telegram_updates")
public class TelegramUpdateEntity {

    @Id
    @Column(name = "update_id")
    private Long updateId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private GroupEntity group;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TelegramUpdateStatus status = TelegramUpdateStatus.PROCESSED;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public TelegramUpdateEntity() {
    }

    public TelegramUpdateEntity(Long updateId, GroupEntity group, TelegramUpdateStatus status) {
        this.updateId = updateId;
        this.group = group;
        this.status = status != null ? status : TelegramUpdateStatus.PROCESSED;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.receivedAt == null) {
            this.receivedAt = now;
        }
        if (this.processedAt == null) {
            this.processedAt = now;
        }
        if (this.status == null) {
            this.status = TelegramUpdateStatus.PROCESSED;
        }
    }

    public Long getUpdateId() {
        return updateId;
    }

    public void setUpdateId(Long updateId) {
        this.updateId = updateId;
    }

    public GroupEntity getGroup() {
        return group;
    }

    public void setGroup(GroupEntity group) {
        this.group = group;
    }

    public TelegramUpdateStatus getStatus() {
        return status;
    }

    public void setStatus(TelegramUpdateStatus status) {
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TelegramUpdateEntity that = (TelegramUpdateEntity) o;
        return Objects.equals(updateId, that.updateId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(updateId);
    }
}
