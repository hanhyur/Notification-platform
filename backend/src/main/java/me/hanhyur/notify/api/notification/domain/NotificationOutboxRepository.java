package me.hanhyur.notify.api.notification.domain;

public interface NotificationOutboxRepository {

    void insert(NotificationOutboxEvent event);

}
