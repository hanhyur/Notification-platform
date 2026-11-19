package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_delivery_attempts")
public class NotificationDeliveryAttemptJpaEntity {

    @Id
    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "dispatch_id", nullable = false)
    private UUID dispatchId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "status", nullable = false, columnDefinition = "text")
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    protected NotificationDeliveryAttemptJpaEntity() {
    }

    NotificationDeliveryAttemptJpaEntity(UUID notificationId, UUID dispatchId, int attemptNo, Instant startedAt) {
        this.attemptId = UUID.randomUUID();
        this.notificationId = notificationId;
        this.dispatchId = dispatchId;
        this.attemptNo = attemptNo;
        this.status = "STARTED";
        this.startedAt = startedAt;
    }
}
