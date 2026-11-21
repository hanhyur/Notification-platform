package me.hanhyur.notify.api.notification.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationDeliveryResultRepository;
import me.hanhyur.notify.api.notification.domain.NotificationDispatchClaimRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationProvider;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import me.hanhyur.notify.api.notification.domain.ProviderResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
    private final Logger logger = (Logger) LoggerFactory.getLogger(NotificationDeliveryService.class);
    private final ListAppender<ILoggingEvent> entries = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        entries.start();
        logger.addAppender(entries);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(entries);
        entries.stop();
    }

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
        assertOnlyClaimLog();
    }

    @Test
    void recordsTheClaimBeforeALookupThatFails() {
        var failure = new IllegalStateException("알림 조회 실패");
        when(claims.tryClaim(event, START)).thenReturn(true);
        when(notifications.findById(event.notificationId())).thenAnswer(invocation -> {
            assertOnlyClaimLog();
            throw failure;
        });

        assertSame(failure, assertThrows(IllegalStateException.class, () -> delivery.deliver(event, START)));
        verifyNoInteractions(provider, results);
    }

    @Test
    void logsNoClaimWhenTheClaimCannotBeSaved() {
        var failure = new IllegalStateException("선점 저장 실패");
        when(claims.tryClaim(event, START)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> delivery.deliver(event, START)));
        assertThat(claimLogs()).isEmpty();
        verifyNoInteractions(notifications, provider, results);
    }

    @Test
    void yieldsToAnotherWorkerWhileItsReplyMayStillArrive() {
        when(notifications.findById(event.notificationId()))
                .thenReturn(Optional.of(notification(NotificationStatus.SENDING, event.dispatchId())));
        assertThrows(IllegalStateException.class, () -> delivery.deliver(event, START.plus(RECOVERY_DELAY).minusMillis(1)));
        assertThat(claimLogs()).isEmpty();
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
        assertOnlyClaimLog();
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
        assertThat(claimLogs()).isEmpty();
        verifyNoInteractions(provider, results);
    }

    private List<ILoggingEvent> claimLogs() {
        return entries.list.stream()
                .filter(entry -> entry.getFormattedMessage().startsWith("dispatch_claimed"))
                .toList();
    }

    private void assertOnlyClaimLog() {
        assertThat(claimLogs()).singleElement().satisfies(entry -> {
            assertThat(entry.getLevel()).isEqualTo(Level.INFO);
            assertThat(entry.getFormattedMessage().split(" ")).containsExactlyInAnyOrder(
                    "dispatch_claimed", "notificationId=" + event.notificationId(),
                    "dispatchId=" + event.dispatchId(), "eventId=" + event.eventId());
            assertThat(entry.getThrowableProxy()).isNull();
        });
    }

    private Notification notification(NotificationStatus status, UUID dispatchId) {
        return new Notification(event.notificationId(), dispatchId, "customer", Channel.EMAIL,
                "recipient@example.com", "제목", "내용", status, null, START, START, event.generation(), 0, 1);
    }
}
