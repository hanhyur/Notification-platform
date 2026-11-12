package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.EntityManager;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Repository
public class JpaNotificationOutboxRepository implements NotificationOutboxRepository {

    private final EntityManager entityManager;

    public JpaNotificationOutboxRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "엔티티 매니저는 필수입니다.");
    }

    @Override
    @Transactional
    public void insert(NotificationOutboxEvent event) {
        Objects.requireNonNull(event, "Outbox 이벤트는 필수입니다.");
        entityManager.persist(NotificationOutboxJpaEntity.fromDomain(event));
        entityManager.flush();
    }
}
