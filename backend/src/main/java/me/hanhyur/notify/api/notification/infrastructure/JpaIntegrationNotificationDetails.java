package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.EntityManager;
import me.hanhyur.notify.api.notification.application.IntegrationNotificationDetail;
import me.hanhyur.notify.api.notification.application.IntegrationNotificationDetails;
import me.hanhyur.notify.api.notification.application.RegistrationOwner;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
class JpaIntegrationNotificationDetails implements IntegrationNotificationDetails {

    private final EntityManager entityManager;
    private final OutboxEventQueries outbox;

    JpaIntegrationNotificationDetails(EntityManager entityManager, OutboxEventQueries outbox) {
        this.entityManager = entityManager;
        this.outbox = outbox;
    }

    @Override
    public IntegrationNotificationDetail find(UUID notificationId, RegistrationOwner owner) {
        var entity = entityManager.getReference(NotificationJpaEntity.class, notificationId);
        entityManager.refresh(entity);
        var notification = entity.toDomain();
        Instant nextAttemptAt = null;
        if (notification.status() == NotificationStatus.RETRY_WAIT) {
            nextAttemptAt = outbox.findByDispatchId(notification.currentDispatchId())
                    .orElseThrow().toDomain().availableAt();
        }
        return IntegrationNotificationDetail.from(notification, nextAttemptAt, entity.acceptedAt(),
                entity.completedAt(), entity.lastErrorCode(), entity.errorSummary(), owner.allowedActions(notification.status()));
    }
}
