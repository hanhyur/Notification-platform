package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import me.hanhyur.notify.api.notification.application.OperatorNotificationHistory;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "notification_operator_actions")
class NotificationOperatorActionJpaEntity {

    @Id
    private UUID id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "operator_id", nullable = false)
    private UUID operatorId;

    @Column(nullable = false, columnDefinition = "text")
    private String action;

    @Column(length = 500)
    private String reason;

    @Column(length = 1000)
    private String evidence;

    @Column(columnDefinition = "text")
    private String resolution;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, columnDefinition = "text")
    private NotificationStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "resulting_status", nullable = false, columnDefinition = "text")
    private NotificationStatus resultingStatus;

    @Column(nullable = false)
    private int generation;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NotificationOperatorActionJpaEntity() {
    }

    OperatorNotificationHistory.Action toAction() {
        return new OperatorNotificationHistory.Action(id, operatorId, action, reason, evidence, resolution,
                previousStatus, resultingStatus, generation, createdAt);
    }
}
