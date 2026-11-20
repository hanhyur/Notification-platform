package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationDeliveryResultRepository;
import me.hanhyur.notify.api.notification.domain.NotificationDispatchClaimRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationProvider;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import me.hanhyur.notify.api.notification.domain.ProviderResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class NotificationDeliveryServiceTest {

    private static final Instant START = Instant.parse("2026-11-19T09:00:00Z");
    private static final Duration RECOVERY_DELAY = Duration.ofSeconds(37);
    private static final Instant FINISH = START.plusSeconds(1);

    private final NotificationDispatchClaimRepository claims = mock();
    private final NotificationRepository notifications = mock();
    private final NotificationProvider provider = mock();
    private final NotificationDeliveryResultRepository results = mock();
    private final NotificationDeliveryService delivery = new NotificationDeliveryService(
            claims, notifications, provider, results, Clock.fixed(FINISH, ZoneOffset.UTC), RECOVERY_DELAY);
    private final NotificationOutboxEvent event = new NotificationOutboxEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, START, START);

    @Test
    void sendsOnlyAfterClaimAndRecordsTheTimeOfTheReply() {
        Notification notification = notification(NotificationStatus.SENDING, event.dispatchId());
        var reply = new ProviderResult(ProviderResult.Outcome.ACCEPTED, "sandbox-" + event.dispatchId());
        when(claims.tryClaim(event, START)).thenReturn(true);
        when(notifications.findById(event.notificationId())).thenReturn(Optional.of(notification));
        when(provider.send(notification)).thenReturn(reply);

        delivery.deliver(event, START);

        var order = inOrder(claims, provider, results);
        order.verify(claims).tryClaim(event, START);
        order.verify(provider).send(notification);
        order.verify(results).finish(event, reply, FINISH);
    }

    @Test
    void yieldsToAnotherWorkerWhileItsReplyMayStillArrive() {
        when(notifications.findById(event.notificationId()))
                .thenReturn(Optional.of(notification(NotificationStatus.SENDING, event.dispatchId())));
        assertThrows(IllegalStateException.class, () -> delivery.deliver(event, START.plus(RECOVERY_DELAY).minusMillis(1)));
        verifyNoInteractions(provider, results);
    }

    @Test
    void aReplayAfterFailureToSaveTheReplyCannotSendAgain() {
        Notification notification = notification(NotificationStatus.SENDING, event.dispatchId());
        var reply = new ProviderResult(ProviderResult.Outcome.ACCEPTED, "sandbox-" + event.dispatchId());
        when(claims.tryClaim(event, START)).thenReturn(true);
        when(notifications.findById(event.notificationId())).thenReturn(Optional.of(notification));
        when(provider.send(notification)).thenReturn(reply);
        var failure = new IllegalStateException("결과 저장 실패");
        doThrow(failure).when(results).finish(event, reply, FINISH);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> delivery.deliver(event, START)));
        Instant redelivery = START.plus(RECOVERY_DELAY);
        delivery.deliver(event, redelivery);

        verify(provider, times(1)).send(notification);
        verify(results).finish(event, new ProviderResult(ProviderResult.Outcome.UNKNOWN, null), redelivery);
    }

    @Test
    void skipsFinishedMissingAndSupersededDeliveries() {
        when(notifications.findById(event.notificationId()))
                .thenReturn(Optional.of(notification(NotificationStatus.ACCEPTED, event.dispatchId())))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(notification(NotificationStatus.SENDING, UUID.randomUUID())));
        for (int i = 0; i < 3; i++) {
            delivery.deliver(event, FINISH);
        }
        verifyNoInteractions(provider, results);
    }

    private Notification notification(NotificationStatus status, UUID dispatchId) {
        return new Notification(event.notificationId(), dispatchId, "customer", Channel.EMAIL,
                "recipient@example.com", "제목", "내용", status, null, START, START, event.generation(), 0, 1);
    }
}
