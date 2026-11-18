package me.hanhyur.notify.api.notification.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationOutboxPublicationRepository {

    List<NotificationOutboxEvent> findPending(Instant now, int limit);

    boolean markPublished(UUID eventId, Instant publishedAt);
}
