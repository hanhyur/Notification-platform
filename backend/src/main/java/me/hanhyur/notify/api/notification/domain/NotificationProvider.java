package me.hanhyur.notify.api.notification.domain;

public interface NotificationProvider {

    ProviderResult send(Notification notification);
}
