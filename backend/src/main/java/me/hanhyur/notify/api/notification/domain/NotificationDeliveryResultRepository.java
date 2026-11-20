package me.hanhyur.notify.api.notification.domain;

import java.time.Instant;

public interface NotificationDeliveryResultRepository {

    void finish(NotificationOutboxEvent event, ProviderResult result, Instant now);
}
