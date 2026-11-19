package me.hanhyur.notify.api.notification.infrastructure;

import org.springframework.data.domain.Limit;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface OutboxEventQueries extends Repository<NotificationOutboxJpaEntity, UUID> {

    List<NotificationOutboxJpaEntity> findByPublishedAtIsNullAndAvailableAtLessThanEqualOrderByAvailableAtAscEventIdAsc(
            Instant now, Limit limit);
}
