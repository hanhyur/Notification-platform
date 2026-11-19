package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.application.NotificationOutboxPublicationService;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

public class OutboxPublicationScheduler {

    private final NotificationOutboxPublicationService service;
    private final int batchSize;

    public OutboxPublicationScheduler(NotificationOutboxPublicationService service, int batchSize) {
        this.service = Objects.requireNonNull(service);
        if (batchSize < 1) {
            throw new IllegalArgumentException("한 번에 발행할 건수는 1 이상이어야 합니다.");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${notification.outbox.polling.fixed-delay}")
    public void publishBatch() {
        service.publishPending(batchSize);
    }
}
