package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import me.hanhyur.notify.api.notification.domain.DeliveryDecision;
import me.hanhyur.notify.api.notification.domain.NotificationDeliveryResultRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxRepository;
import me.hanhyur.notify.api.notification.domain.ProviderResult;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Repository
public class JpaNotificationDeliveryResultRepository implements NotificationDeliveryResultRepository {

    private final EntityManager entityManager;
    private final DeliveryAttemptQueries attempts;
    private final NotificationOutboxRepository outbox;

    public JpaNotificationDeliveryResultRepository(EntityManager entityManager, DeliveryAttemptQueries attempts,
                                                  NotificationOutboxRepository outbox) {
        this.entityManager = entityManager;
        this.attempts = attempts;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public void finish(NotificationOutboxEvent event, ProviderResult result, Instant now) {
        var notification = entityManager.find(NotificationJpaEntity.class, event.notificationId(),
                LockModeType.PESSIMISTIC_WRITE);
        if (notification == null || !notification.isSending(event) || !matchesStoredEvent(event)) {
            return;
        }
        var attempt = attempts.findByNotificationIdAndDispatchIdAndStatus(
                event.notificationId(), event.dispatchId(), "STARTED");
        if (attempt.isEmpty()) {
            return;
        }

        var decision = DeliveryDecision.decide(result.outcome(), notification.retryCount());
        Instant finishedAt = attempt.get().finish(decision, result.reference(), now);
        notification.finish(decision, finishedAt).ifPresent(outbox::insert);
    }

    private boolean matchesStoredEvent(NotificationOutboxEvent event) {
        var entry = entityManager.find(NotificationOutboxJpaEntity.class, event.eventId());
        if (entry == null) {
            return false;
        }
        var stored = entry.toDomain();
        return stored.notificationId().equals(event.notificationId()) && stored.dispatchId().equals(event.dispatchId())
                && stored.generation() == event.generation();
    }
}
