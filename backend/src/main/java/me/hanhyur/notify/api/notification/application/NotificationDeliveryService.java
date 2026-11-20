package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationDeliveryResultRepository;
import me.hanhyur.notify.api.notification.domain.NotificationDispatchClaimRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationProvider;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import me.hanhyur.notify.api.notification.domain.ProviderResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public class NotificationDeliveryService {

    private final NotificationDispatchClaimRepository claims;
    private final NotificationRepository notifications;
    private final NotificationProvider provider;
    private final NotificationDeliveryResultRepository results;
    private final Clock clock;
    private final Duration recoveryDelay;

    public NotificationDeliveryService(
            NotificationDispatchClaimRepository claims, NotificationRepository notifications,
            NotificationProvider provider, NotificationDeliveryResultRepository results,
            Clock clock, Duration recoveryDelay
    ) {
        this.claims = claims;
        this.notifications = notifications;
        this.provider = provider;
        this.results = results;
        this.clock = clock;
        this.recoveryDelay = recoveryDelay;
    }

    @Transactional(propagation = Propagation.NEVER)
    public void deliver(NotificationOutboxEvent event, Instant now) {
        boolean claimed = claims.tryClaim(event, now);
        var current = notifications.findById(event.notificationId())
                .filter(notification -> notification.status() == NotificationStatus.SENDING
                        && notification.currentDispatchId().equals(event.dispatchId())
                        && notification.generation() == event.generation());
        if (current.isEmpty()) {
            return;
        }

        Notification notification = current.get();
        if (claimed) {
            ProviderResult reply = provider.send(notification);
            results.finish(event, reply, clock.instant());
        } else {
            recoverUnfinished(event, notification, now);
        }
    }

    private void recoverUnfinished(NotificationOutboxEvent event, Notification notification, Instant now) {
        if (now.isBefore(notification.updatedAt().plus(recoveryDelay))) {
            throw new IllegalStateException("이미 맡은 작업자의 응답을 기다립니다.");
        }
        results.finish(event, new ProviderResult(ProviderResult.Outcome.UNKNOWN, null), now);
    }
}
