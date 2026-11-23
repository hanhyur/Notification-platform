package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OperatorActionStore {

    record Receipt(String requestHash, UUID notificationId) {}

    Optional<Receipt> reserve(UUID operatorId, String commandType, UUID key, UUID notificationId, String requestHash);

    Optional<Notification> lock(UUID notificationId);

    void retry(UUID notificationId, UUID newDispatchId, Instant at);

    void resolve(UUID notificationId, NotificationStatus resultingStatus, Instant at);

    void record(UUID notificationId, OperatorNotificationHistory.Action action);
}
