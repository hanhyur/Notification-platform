package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublicationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

public class NotificationOutboxPublicationService {

    private final NotificationOutboxPublicationRepository repository;
    private final NotificationOutboxPublisher publisher;
    private final Clock clock;

    public NotificationOutboxPublicationService(
            NotificationOutboxPublicationRepository repository,
            NotificationOutboxPublisher publisher,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "발행 저장소는 필수입니다.");
        this.publisher = Objects.requireNonNull(publisher, "이벤트 발행기는 필수입니다.");
        this.clock = Objects.requireNonNull(clock, "시계는 필수입니다.");
    }

    @Transactional(propagation = Propagation.NEVER)
    public int publishPending(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("발행 건수는 1 이상이어야 합니다.");
        }

        int count = 0;
        List<NotificationOutboxEvent> events =
                repository.findPending(clock.instant(), limit);

        for (NotificationOutboxEvent event : events) {
            if (publishOne(event)) {
                count++;
            }
        }

        return count;
    }

    private boolean publishOne(NotificationOutboxEvent event) {
        publisher.publish(event);

        return repository.markPublished(event.eventId(), clock.instant());
    }
}
