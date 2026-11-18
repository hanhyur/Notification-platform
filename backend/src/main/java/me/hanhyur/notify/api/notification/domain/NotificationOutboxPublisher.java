package me.hanhyur.notify.api.notification.domain;

@FunctionalInterface
public interface NotificationOutboxPublisher {

    void publish(NotificationOutboxEvent event);
}
