package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.EntityManager;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublicationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Repository
public class JpaNotificationOutboxRepository
        implements NotificationOutboxRepository, NotificationOutboxPublicationRepository {

    private final EntityManager entityManager;
    private final OutboxEventQueries events;

    public JpaNotificationOutboxRepository(EntityManager entityManager, OutboxEventQueries events) {
        this.entityManager = Objects.requireNonNull(entityManager, "엔티티 매니저는 필수입니다.");
        this.events = Objects.requireNonNull(events);
    }

    @Override
    @Transactional
    public void insert(NotificationOutboxEvent event) {
        Objects.requireNonNull(event, "Outbox 이벤트는 필수입니다.");
        entityManager.persist(NotificationOutboxJpaEntity.fromDomain(event));
        entityManager.flush();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationOutboxEvent> findPending(Instant now, int limit) {
        Objects.requireNonNull(now, "조회 기준 시각은 필수입니다.");
        if (limit < 1) {
            throw new IllegalArgumentException("조회 건수는 1 이상이어야 합니다.");
        }

        return events.findByPublishedAtIsNullAndAvailableAtLessThanEqualOrderByAvailableAtAscEventIdAsc(
                        now, Limit.of(limit))
                .stream()
                .map(NotificationOutboxJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public boolean markPublished(UUID eventId, Instant publishedAt) {
        Objects.requireNonNull(eventId, "이벤트 식별자는 필수입니다.");
        Objects.requireNonNull(publishedAt, "발행 완료 시각은 필수입니다.");

        int updateCount = entityManager.createQuery("""
                        update NotificationOutboxJpaEntity e
                        set e.publishedAt = :publishedAt
                        where e.eventId = :eventId
                        and e.publishedAt is null
                        and e.availableAt <= :publishedAt
                        """)
                .setParameter("publishedAt", publishedAt)
                .setParameter("eventId", eventId)
                .executeUpdate();

        return updateCount > 0;
    }
}
