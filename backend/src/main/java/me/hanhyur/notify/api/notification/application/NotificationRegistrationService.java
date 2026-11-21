package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationFactory;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxRepository;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class NotificationRegistrationService {

    private final NotificationFactory factory;
    private final NotificationRepository repository;
    private final NotificationOutboxRepository outboxRepository;
    private final ApplicationEventPublisher events;

    public NotificationRegistrationService(
            NotificationFactory factory,
            NotificationRepository repository,
            NotificationOutboxRepository outboxRepository,
            ApplicationEventPublisher events
    ) {
        this.factory = Objects.requireNonNull(factory, "알림 팩토리는 필수입니다.");
        this.repository = Objects.requireNonNull(repository, "알림 저장소는 필수입니다.");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "Outbox 저장소는 필수입니다.");
        this.events = Objects.requireNonNull(events, "이벤트 발행기는 필수입니다.");
    }

    @Transactional
    public Notification register(RegisterNotificationCommand command) {
        Objects.requireNonNull(command, "알림 등록 명령은 필수입니다.");

        Notification notification = factory.create(
                command.customerId(),
                command.channel(),
                command.recipient(),
                command.title(),
                command.content(),
                command.scheduledAt()
        );

        repository.insert(notification);
        recordOutboxEvent(notification);

        return notification;
    }

    private void recordOutboxEvent(Notification notification) {
        NotificationOutboxEvent event = NotificationOutboxEvent.forRegistration(notification);

        outboxRepository.insert(event);

        NotificationRegistered registered = new NotificationRegistered(
                event.notificationId(),
                event.dispatchId(),
                event.eventId()
        );

        events.publishEvent(registered);
    }
}
