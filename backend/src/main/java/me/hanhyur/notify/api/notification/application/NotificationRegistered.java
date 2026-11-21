package me.hanhyur.notify.api.notification.application;

import java.util.UUID;

public record NotificationRegistered(UUID notificationId, UUID dispatchId, UUID eventId) {
}
