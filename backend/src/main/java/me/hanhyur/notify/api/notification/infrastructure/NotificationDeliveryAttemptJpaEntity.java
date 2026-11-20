package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import me.hanhyur.notify.api.notification.domain.DeliveryDecision;

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

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "failure_category", length = 100)
    private String failureCategory;

    @Column(name = "provider_reference", length = 200)
    private String providerReference;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_summary", length = 500)
    private String errorSummary;

    protected NotificationDeliveryAttemptJpaEntity() {
    }

    Instant finish(DeliveryDecision decision, String reference, Instant now) {
        finishedAt = now.isBefore(startedAt) ? startedAt : now;
        status = decision.attemptStatus();
        failureCategory = decision.errorCode();
        providerReference = reference;
        errorCode = decision.errorCode();
        errorSummary = decision.errorSummary();
        return finishedAt;
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
