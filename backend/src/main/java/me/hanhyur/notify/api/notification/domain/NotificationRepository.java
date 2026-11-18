package me.hanhyur.notify.api.notification.domain;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository {

    void insert(Notification notification);

    Optional<Notification> findById(UUID notificationId);

}
