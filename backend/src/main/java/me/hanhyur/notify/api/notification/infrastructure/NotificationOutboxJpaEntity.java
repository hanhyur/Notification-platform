package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "notification_outbox")
public class NotificationOutboxJpaEntity {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "dispatch_id", nullable = false)
    private UUID dispatchId;

    @Column(name = "generation", nullable = false)
    private int generation;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected NotificationOutboxJpaEntity() {
    }

    public static NotificationOutboxJpaEntity fromDomain(NotificationOutboxEvent event) {
        Objects.requireNonNull(event, "Outbox 이벤트는 필수입니다.");

        NotificationOutboxJpaEntity entity = new NotificationOutboxJpaEntity();

        entity.eventId = event.eventId();
        entity.notificationId = event.notificationId();
        entity.dispatchId = event.dispatchId();
        entity.generation = event.generation();
        entity.occurredAt = event.occurredAt();
        entity.availableAt = event.availableAt();

        return entity;
    }

    public NotificationOutboxEvent toDomain() {
        return new NotificationOutboxEvent(
                eventId,
                notificationId,
                dispatchId,
                generation,
                occurredAt,
                availableAt
        );
    }
}
