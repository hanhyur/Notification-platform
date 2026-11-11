package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.EntityManager;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaNotificationRepository implements NotificationRepository {

    private final EntityManager entityManager;

    public JpaNotificationRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(
                entityManager, "엔티티 매니저는 필수입니다."
        );
    }

    @Override
    @Transactional
    public void insert(Notification notification) {
        Objects.requireNonNull(notification, "알림은 필수입니다.");

        NotificationJpaEntity entity =
                NotificationJpaEntity.fromDomain(notification);

        entityManager.persist(entity);
        entityManager.flush();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Notification> findById(UUID notificationId) {
        Objects.requireNonNull(notificationId, "알림 식별자는 필수입니다.");

        NotificationJpaEntity entity = entityManager.find(NotificationJpaEntity.class, notificationId);

        return Optional.ofNullable(entity)
                .map(NotificationJpaEntity::toDomain);
    }
}
