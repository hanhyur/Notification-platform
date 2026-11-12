package me.hanhyur.notify.api.notification.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NotificationOutboxEventTest {

    private static final Instant NOW = Instant.parse("2026-11-11T09:00:00.123456Z");

    @Test
    void copiesDispatchReferencesAndMakesImmediateEventAvailableAtCreation() {
        Notification notification = notification(NOW, null);

        NotificationOutboxEvent event = NotificationOutboxEvent.forRegistration(notification);

        assertAll(
                () -> assertEquals(notification.notificationId(), event.notificationId()),
                () -> assertEquals(notification.currentDispatchId(), event.dispatchId()),
                () -> assertEquals(notification.generation(), event.generation()),
                () -> assertEquals(NOW, event.occurredAt()),
                () -> assertEquals(NOW, event.availableAt())
        );
    }

    @Test
    void keepsScheduleAsTheAvailabilityOfTheEvent() {
        Instant scheduledAt = NOW.plusSeconds(60);
        Notification notification = notification(NOW, scheduledAt);

        NotificationOutboxEvent event = NotificationOutboxEvent.forRegistration(notification);

        assertEquals(NOW, event.occurredAt());
        assertEquals(scheduledAt, event.availableAt());
    }

    @Test
    void generatesItsOwnUuidInsteadOfReusingNotificationOrDispatchId() {
        Notification notification = notification(NOW, null);

        NotificationOutboxEvent event = NotificationOutboxEvent.forRegistration(notification);

        assertNotNull(event.eventId());
        assertEquals(4, event.eventId().version());
        assertNotEquals(notification.notificationId(), event.eventId());
        assertNotEquals(notification.currentDispatchId(), event.eventId());
    }

    @Test
    void generatesDifferentEventIdsForIndependentCreations() {
        Notification notification = notification(NOW, null);

        NotificationOutboxEvent first = NotificationOutboxEvent.forRegistration(notification);
        NotificationOutboxEvent second = NotificationOutboxEvent.forRegistration(notification);

        assertNotEquals(first.eventId(), second.eventId());
        assertEquals(first.dispatchId(), second.dispatchId());
    }

    @Test
    void doesNotExposeRecipientOrPayload() {
        Notification notification = notification(NOW, null);

        String description = NotificationOutboxEvent.forRegistration(notification).toString();

        assertFalse(description.contains(notification.customerId()));
        assertFalse(description.contains(notification.recipient()));
        assertFalse(description.contains(notification.title()));
        assertFalse(description.contains(notification.content()));
    }

    @Test
    void preservesInstantPrecisionBeforeThePersistenceBoundary() {
        Instant preciseNow = Instant.parse("2026-11-11T09:00:00.123456789Z");
        Instant scheduledAt = preciseNow.plusNanos(1);

        NotificationOutboxEvent event = NotificationOutboxEvent.forRegistration(
                notification(preciseNow, scheduledAt)
        );

        assertEquals(preciseNow, event.occurredAt());
        assertEquals(scheduledAt, event.availableAt());
    }

    @Test
    void rejectsNullNotification() {
        assertThrows(
                NullPointerException.class,
                () -> NotificationOutboxEvent.forRegistration(null)
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveGeneration(int generation) {
        assertThrows(IllegalArgumentException.class, () -> new NotificationOutboxEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                generation, NOW, NOW
        ));
    }

    @Test
    void rejectsAvailabilityBeforeTheEventWasCreated() {
        assertThrows(IllegalArgumentException.class, () -> new NotificationOutboxEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, NOW, NOW.minusNanos(1)
        ));
    }

    private static Notification notification(Instant now, Instant scheduledAt) {
        NotificationFactory factory = new NotificationFactory(
                Clock.fixed(now, ZoneOffset.UTC), new NotificationSchedulePolicy()
        );
        return factory.create(
                "customer-1001", Channel.EMAIL, "sample@example.com",
                "예약 안내", "예약이 접수되었습니다.", scheduledAt
        );
    }
}
