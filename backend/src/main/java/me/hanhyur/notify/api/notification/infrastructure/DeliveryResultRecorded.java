package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.domain.NotificationStatus;

import java.util.UUID;

record DeliveryResultRecorded(UUID notificationId, UUID dispatchId, UUID eventId, NotificationStatus status) {
}
