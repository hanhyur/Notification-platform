package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.EntityManager;
import me.hanhyur.notify.api.notification.domain.NotificationDispatchClaimRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Repository
public class JpaNotificationDispatchClaimRepository implements NotificationDispatchClaimRepository {

    private final EntityManager entityManager;
    private final DeliveryAttemptQueries attempts;

    public JpaNotificationDispatchClaimRepository(EntityManager entityManager, DeliveryAttemptQueries attempts) {
        this.entityManager = Objects.requireNonNull(entityManager);
        this.attempts = Objects.requireNonNull(attempts);
    }

    @Override
    @Transactional
    public boolean tryClaim(NotificationOutboxEvent event, Instant now) {
        Objects.requireNonNull(event);
        Objects.requireNonNull(now);
        if (markSending(event, now) == 0) {
            return false;
        }

        int lastAttemptNo = attempts.findFirstByNotificationIdOrderByAttemptNoDesc(event.notificationId())
                .map(DeliveryAttemptQueries.AttemptNumber::attemptNo)
                .orElse(0);
        entityManager.persist(new NotificationDeliveryAttemptJpaEntity(
                event.notificationId(), event.dispatchId(), lastAttemptNo + 1, now));
        return true;
    }

    private int markSending(NotificationOutboxEvent event, Instant now) {
        return entityManager.createQuery("""
                    update NotificationJpaEntity n
                    set n.status = :sending,
                        n.updatedAt = :now,
                        n.version = n.version + 1
                    where n.notificationId = :notificationId
                      and n.currentDispatchId = :dispatchId
                      and n.generation = :generation
                      and (
                          n.status in (:queued, :retryWait)
                          or (n.status = :scheduled and n.scheduledAt <= :now)
                      )
                      and exists (
                          select 1
                          from NotificationOutboxJpaEntity e
                          where e.eventId = :eventId
                            and e.notificationId = n.notificationId
                            and e.dispatchId = n.currentDispatchId
                            and e.generation = n.generation
                            and e.availableAt <= :now
                      )
                    """)
                .setParameter("sending", NotificationStatus.SENDING)
                .setParameter("now", now)
                .setParameter("notificationId", event.notificationId())
                .setParameter("dispatchId", event.dispatchId())
                .setParameter("generation", event.generation())
                .setParameter("queued", NotificationStatus.QUEUED)
                .setParameter("retryWait", NotificationStatus.RETRY_WAIT)
                .setParameter("scheduled", NotificationStatus.SCHEDULED)
                .setParameter("eventId", event.eventId())
                .executeUpdate();
    }
}
