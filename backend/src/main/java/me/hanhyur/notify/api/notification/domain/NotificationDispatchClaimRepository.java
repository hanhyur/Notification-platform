package me.hanhyur.notify.api.notification.domain;

import java.time.Instant;

public interface NotificationDispatchClaimRepository {

    boolean tryClaim(NotificationOutboxEvent event, Instant now);
}
