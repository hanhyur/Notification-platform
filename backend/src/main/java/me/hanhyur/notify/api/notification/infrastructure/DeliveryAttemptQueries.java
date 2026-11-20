package me.hanhyur.notify.api.notification.infrastructure;

import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

interface DeliveryAttemptQueries extends Repository<NotificationDeliveryAttemptJpaEntity, UUID> {

    Optional<AttemptNumber> findFirstByNotificationIdOrderByAttemptNoDesc(UUID notificationId);

    Optional<NotificationDeliveryAttemptJpaEntity> findByNotificationIdAndDispatchIdAndStatus(
            UUID notificationId, UUID dispatchId, String status);

    record AttemptNumber(int attemptNo) {}
}
