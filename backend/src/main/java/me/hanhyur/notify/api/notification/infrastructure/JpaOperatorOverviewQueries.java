package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import me.hanhyur.notify.api.notification.application.OperatorOverviewQueries;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
class JpaOperatorOverviewQueries implements OperatorOverviewQueries {

    private static final Set<NotificationStatus> PENDING = Set.of(
            NotificationStatus.QUEUED, NotificationStatus.SENDING, NotificationStatus.RETRY_WAIT);

    private final EntityManager entityManager;

    JpaOperatorOverviewQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Dashboard dashboard(Instant from, Instant to, Instant now) {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(StatusCount.class);
        var notification = query.from(NotificationJpaEntity.class);
        var status = notification.<NotificationStatus>get("status");
        var createdAt = notification.<Instant>get("createdAt");
        var inWindow = createdInWindow(cb, createdAt, from, to);
        var dispatch = currentDispatch(cb, notification, NotificationOutboxJpaEntity.class);
        var attempt = currentDispatch(cb, notification, NotificationDeliveryAttemptJpaEntity.class);
        var dueAt = cb.<Instant>selectCase()
                .when(cb.equal(status, NotificationStatus.SENDING), attempt.<Instant>get("startedAt"))
                .otherwise(dispatch.<Instant>get("availableAt"));
        query.select(cb.construct(StatusCount.class, status,
                        cb.sum(cb.<Long>selectCase().when(inWindow, 1L).otherwise(0L)),
                        cb.count(notification), cb.least(dueAt)))
                .where(cb.or(inWindow, status.in(PENDING)))
                .groupBy(status);

        var counts = new EnumMap<NotificationStatus, Long>(NotificationStatus.class);
        for (var value : NotificationStatus.values()) {
            counts.put(value, 0L);
        }
        long totalCreated = 0;
        long pendingCount = 0;
        long oldestDueLagSeconds = 0;
        for (var row : entityManager.createQuery(query).getResultList()) {
            counts.put(row.status(), row.inWindow());
            totalCreated += row.inWindow();
            if (PENDING.contains(row.status())) {
                pendingCount += row.all();
                if (row.earliestDueAt() != null) {
                    long lag = Duration.between(row.earliestDueAt(), now).getSeconds();
                    oldestDueLagSeconds = Math.max(oldestDueLagSeconds, lag);
                }
            }
        }
        return new Dashboard(new Window(from, to), Collections.unmodifiableMap(counts), totalCreated,
                pendingCount, oldestDueLagSeconds, now);
    }

    @Override
    public List<NotificationSummary> notifications(Filter filter, Instant beforeCreatedAt, UUID beforeId, int limit) {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(NotificationSummary.class);
        var notification = query.from(NotificationJpaEntity.class);
        var dispatch = currentDispatch(cb, notification, NotificationOutboxJpaEntity.class);
        var createdAt = notification.<Instant>get("createdAt");
        var id = notification.<UUID>get("notificationId");

        var conditions = new ArrayList<Predicate>();
        if (filter.from() != null) {
            conditions.add(createdInWindow(cb, createdAt, filter.from(), filter.to()));
        }
        if (filter.customerId() != null) {
            conditions.add(cb.equal(notification.get("customerId"), filter.customerId()));
        }
        if (filter.channel() != null) {
            conditions.add(cb.equal(notification.get("channel"), filter.channel()));
        }
        if (!filter.statuses().isEmpty()) {
            conditions.add(notification.get("status").in(filter.statuses()));
        }
        if (beforeCreatedAt != null) {
            conditions.add(cb.or(cb.lessThan(createdAt, beforeCreatedAt),
                    cb.and(cb.equal(createdAt, beforeCreatedAt), cb.lessThan(id, beforeId))));
        }
        query.select(cb.construct(NotificationSummary.class, id, notification.get("customerId"),
                        notification.get("channel"), notification.get("recipient"), notification.get("status"),
                        createdAt, notification.get("updatedAt"), notification.get("scheduledAt"),
                        dispatch.get("availableAt"), notification.get("acceptedAt"), notification.get("completedAt")))
                .where(conditions)
                .orderBy(cb.desc(createdAt), cb.desc(id));
        return entityManager.createQuery(query).setMaxResults(limit).getResultList();
    }

    private Predicate createdInWindow(CriteriaBuilder cb, Path<Instant> createdAt, Instant from, Instant to) {
        return cb.and(cb.greaterThanOrEqualTo(createdAt, ceilingToMicrosecond(from)),
                cb.lessThan(createdAt, ceilingToMicrosecond(to)));
    }

    private Instant ceilingToMicrosecond(Instant value) {
        Instant truncated = value.truncatedTo(ChronoUnit.MICROS);
        return truncated.equals(value) ? value : truncated.plus(1, ChronoUnit.MICROS);
    }

    private <T> Join<NotificationJpaEntity, T> currentDispatch(CriteriaBuilder cb,
            Root<NotificationJpaEntity> notification, Class<T> entity) {
        var join = notification.join(entity, JoinType.LEFT);
        join.on(cb.equal(join.get("dispatchId"), notification.get("currentDispatchId")),
                cb.equal(join.get("notificationId"), notification.get("notificationId")));
        return join;
    }

    private record StatusCount(NotificationStatus status, long inWindow, long all, Instant earliestDueAt) {}
}
