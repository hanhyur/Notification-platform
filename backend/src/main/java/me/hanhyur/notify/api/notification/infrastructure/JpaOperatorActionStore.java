package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import me.hanhyur.notify.api.notification.application.OperatorActionStore;
import me.hanhyur.notify.api.notification.application.OperatorNotificationHistory;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxRepository;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaOperatorActionStore implements OperatorActionStore {

    private final EntityManager entityManager;
    private final NotificationOutboxRepository outbox;

    JpaOperatorActionStore(EntityManager entityManager, NotificationOutboxRepository outbox) {
        this.entityManager = entityManager;
        this.outbox = outbox;
    }

    @Override
    public Optional<Receipt> reserve(UUID operatorId, String commandType, UUID key, UUID notificationId,
                                     String requestHash) {
        int inserted = entityManager.createNativeQuery("""
                        INSERT INTO operator_action_requests
                            (operator_id, command_type, idempotency_key, notification_id, request_hash)
                        VALUES (:operator, :command, :key, :notification, :hash)
                        ON CONFLICT (operator_id, command_type, idempotency_key) DO NOTHING
                        """)
                .setParameter("operator", operatorId)
                .setParameter("command", commandType)
                .setParameter("key", key)
                .setParameter("notification", notificationId)
                .setParameter("hash", requestHash)
                .executeUpdate();
        if (inserted == 1) {
            return Optional.empty();
        }

        var receiptKey = new OperatorActionRequestJpaEntity.Key(operatorId, commandType, key);
        var existing = entityManager.find(OperatorActionRequestJpaEntity.class, receiptKey);
        if (existing == null) {
            throw new ConcurrencyFailureException("중복 요청 확인 중 조치 접수 기록이 삭제되었습니다.");
        }
        return Optional.of(existing.receipt());
    }

    @Override
    public Optional<Notification> lock(UUID notificationId) {
        return Optional.ofNullable(entityManager.find(NotificationJpaEntity.class, notificationId,
                LockModeType.PESSIMISTIC_WRITE)).map(NotificationJpaEntity::toDomain);
    }

    @Override
    public void retry(UUID notificationId, UUID newDispatchId, Instant at) {
        var notification = entityManager.getReference(NotificationJpaEntity.class, notificationId);
        outbox.insert(notification.retryManually(newDispatchId, at));
    }

    @Override
    public void resolve(UUID notificationId, NotificationStatus resultingStatus, Instant at) {
        var notification = entityManager.getReference(NotificationJpaEntity.class, notificationId);
        notification.resolveUnknown(resultingStatus, at);
    }

    @Override
    public void record(UUID notificationId, OperatorNotificationHistory.Action action) {
        entityManager.persist(new NotificationOperatorActionJpaEntity(notificationId, action));
    }
}
