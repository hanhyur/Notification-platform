package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.EntityManager;
import me.hanhyur.notify.api.notification.application.IntegrationNotificationDetail;
import me.hanhyur.notify.api.notification.application.IntegrationNotificationDetails;
import me.hanhyur.notify.api.notification.application.OperatorNotificationHistory;
import me.hanhyur.notify.api.notification.application.RegistrationOwner;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaNotificationHistory implements IntegrationNotificationDetails, OperatorNotificationHistory {

    private final EntityManager entityManager;
    private final OutboxEventQueries outbox;

    JpaNotificationHistory(EntityManager entityManager, OutboxEventQueries outbox) {
        this.entityManager = entityManager;
        this.outbox = outbox;
    }

    @Override
    public IntegrationNotificationDetail find(UUID notificationId, RegistrationOwner owner) {
        var entity = entityManager.getReference(NotificationJpaEntity.class, notificationId);
        entityManager.refresh(entity);
        return toDetail(entity, owner);
    }

    @Override
    public Optional<IntegrationNotificationDetail> detail(UUID notificationId, UUID operatorId) {
        return Optional.ofNullable(entityManager.find(NotificationJpaEntity.class, notificationId))
                .map(entity -> toDetail(entity, RegistrationOwner.operator(operatorId)));
    }

    private IntegrationNotificationDetail toDetail(NotificationJpaEntity entity, RegistrationOwner owner) {
        var notification = entity.toDomain();
        Instant nextAttemptAt = null;
        if (notification.status() == NotificationStatus.RETRY_WAIT) {
            nextAttemptAt = outbox.findByDispatchId(notification.currentDispatchId())
                    .orElseThrow().toDomain().availableAt();
        }
        return IntegrationNotificationDetail.from(notification, nextAttemptAt, entity.acceptedAt(),
                entity.completedAt(), entity.lastErrorCode(), entity.errorSummary(), owner.allowedActions(notification.status()));
    }

    @Override
    public boolean notificationExists(UUID notificationId) {
        var builder = entityManager.getCriteriaBuilder();
        var query = builder.createQuery(UUID.class);
        var id = query.from(NotificationJpaEntity.class).<UUID>get("notificationId");
        query.select(id).where(builder.equal(id, notificationId));
        return !entityManager.createQuery(query).getResultList().isEmpty();
    }

    @Override
    public List<Attempt> attempts(UUID notificationId, Integer beforeAttemptNo, UUID beforeId, int limit) {
        return historyRows(NotificationDeliveryAttemptJpaEntity.class, notificationId, "attemptNo", beforeAttemptNo,
                "attemptId", beforeId, limit).stream().map(NotificationDeliveryAttemptJpaEntity::toAttempt).toList();
    }

    @Override
    public List<Action> actions(UUID notificationId, Instant beforeCreatedAt, UUID beforeId, int limit) {
        return historyRows(NotificationOperatorActionJpaEntity.class, notificationId, "createdAt", beforeCreatedAt,
                "id", beforeId, limit).stream().map(NotificationOperatorActionJpaEntity::toAction).toList();
    }

    private <T, K extends Comparable<? super K>> List<T> historyRows(Class<T> entityType, UUID notificationId,
            String orderField, K beforeOrder, String idField, UUID beforeId, int limit) {
        var builder = entityManager.getCriteriaBuilder();
        var query = builder.createQuery(entityType);
        var row = query.from(entityType);
        var order = row.<K>get(orderField);
        var id = row.<UUID>get(idField);
        var condition = builder.equal(row.get("notificationId"), notificationId);
        if (beforeOrder != null) {
            var earlier = builder.or(builder.lessThan(order, beforeOrder),
                    builder.and(builder.equal(order, beforeOrder), builder.lessThan(id, beforeId)));
            condition = builder.and(condition, earlier);
        }
        query.select(row).where(condition).orderBy(builder.desc(order), builder.desc(id));
        return entityManager.createQuery(query).setMaxResults(limit).getResultList();
    }
}
