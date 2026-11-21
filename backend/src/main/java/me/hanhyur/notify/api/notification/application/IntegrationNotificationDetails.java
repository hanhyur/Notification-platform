package me.hanhyur.notify.api.notification.application;

import java.util.UUID;

public interface IntegrationNotificationDetails {

    IntegrationNotificationDetail find(UUID notificationId);
}
