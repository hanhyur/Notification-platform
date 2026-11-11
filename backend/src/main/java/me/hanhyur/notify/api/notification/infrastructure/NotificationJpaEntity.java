package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class NotificationJpaEntity {

    @Id
    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "current_dispatch_id", nullable = false)
    private UUID currentDispatchId;

    @Column(name = "customer_id", nullable = false, length = 100)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, columnDefinition = "text")
    private Channel channel;

    @Column(name = "recipient", nullable = false, length = 320)
    private String recipient;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "content", nullable = false, length = 2000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "text")
    private NotificationStatus status;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "generation", nullable = false)
    private int generation;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "version", nullable = false)
    private long version;

    protected NotificationJpaEntity() {
    }

    public static NotificationJpaEntity fromDomain(Notification notification) {
        Objects.requireNonNull(notification, "알림은 필수입니다.");

        NotificationJpaEntity entity = new NotificationJpaEntity();
        entity.notificationId = notification.notificationId();
        entity.currentDispatchId = notification.currentDispatchId();
        entity.customerId = notification.customerId();
        entity.channel = notification.channel();
        entity.recipient = notification.recipient();
        entity.title = notification.title();
        entity.content = notification.content();
        entity.status = notification.status();
        entity.scheduledAt = notification.scheduledAt();
        entity.createdAt = notification.createdAt();
        entity.updatedAt = notification.updatedAt();
        entity.generation = notification.generation();
        entity.retryCount = notification.retryCount();
        entity.version = notification.version();

        return entity;
    }

    public Notification toDomain() {
        return new Notification(
                notificationId,
                currentDispatchId,
                customerId,
                channel,
                recipient,
                title,
                content,
                status,
                scheduledAt,
                createdAt,
                updatedAt,
                generation,
                retryCount,
                version
        );
    }
}
