package me.hanhyur.notify.api.notification.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class NotificationFactoryTest {

    private static final Instant NOW = Instant.parse("2026-11-08T09:00:00Z");
    private static final String CUSTOMER_ID = "customer-1001";
    private static final String RECIPIENT = "sample@example.com";
    private static final String TITLE = "예약 안내";
    private static final String CONTENT = "예약이 접수되었습니다.";

    private final NotificationFactory factory = new NotificationFactory(
            Clock.fixed(NOW, ZoneOffset.UTC),
            new NotificationSchedulePolicy()
    );

    @Test
    void createsQueuedNotificationWithoutSchedule() {
        Notification actual = create(factory, null);

        assertAll(
                () -> assertEquals(NotificationStatus.QUEUED, actual.status()),
                () -> assertNull(actual.scheduledAt()),
                () -> assertEquals(CUSTOMER_ID, actual.customerId()),
                () -> assertEquals(Channel.EMAIL, actual.channel()),
                () -> assertEquals(RECIPIENT, actual.recipient()),
                () -> assertEquals(TITLE, actual.title()),
                () -> assertEquals(CONTENT, actual.content())
        );
    }

    @Test
    void createsScheduledNotificationForFutureTime() {
        Instant scheduledAt = NOW.plusSeconds(1);
        Notification actual = create(factory, scheduledAt);

        assertAll(
                () -> assertEquals(NotificationStatus.SCHEDULED, actual.status()),
                () -> assertEquals(scheduledAt, actual.scheduledAt())
        );
    }

    @ParameterizedTest
    @MethodSource("invalidSchedules")
    void rejectsInvalidSchedule(Instant scheduledAt) {
        assertThrows(
                IllegalArgumentException.class,
                () -> create(factory, scheduledAt)
        );
    }

    static Stream<Instant> invalidSchedules() {
        return Stream.of(
                NOW,
                NOW.minusNanos(1),
                NOW.plus(Duration.ofDays(30)).plusNanos(1)
        );
    }

    @Test
    void acceptsExactlyThirtyDaysLater() {
        Instant scheduledAt = NOW.plus(Duration.ofDays(30));
        Notification actual = create(factory, scheduledAt);

        assertEquals(NotificationStatus.SCHEDULED, actual.status());
        assertEquals(scheduledAt, actual.scheduledAt());
    }

    @Test
    void usesClockForCreationAndUpdateTimes() {
        Notification actual = create(factory, null);

        assertEquals(NOW, actual.createdAt());
        assertEquals(NOW, actual.updatedAt());
    }

    @Test
    void usesOneInstantForValidationAndTimestamps() {
        NotificationFactory advancingFactory = new NotificationFactory(
                new AdvancingClock(NOW),
                new NotificationSchedulePolicy()
        );

        Notification actual = create(advancingFactory, NOW.plusNanos(1));

        assertAll(
                () -> assertEquals(NotificationStatus.SCHEDULED, actual.status()),
                () -> assertEquals(NOW, actual.createdAt()),
                () -> assertEquals(NOW, actual.updatedAt())
        );
    }

    @Test
    void keepsInstantWhenClockHasAnotherZone() {
        NotificationFactory seoulFactory = new NotificationFactory(
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")),
                new NotificationSchedulePolicy()
        );

        Notification actual = create(seoulFactory, null);

        assertEquals(NOW, actual.createdAt());
        assertEquals(NOW, actual.updatedAt());
    }

    @Test
    void generatesSeparateNotificationAndDispatchIds() {
        Notification actual = create(factory, null);

        assertNotNull(actual.notificationId());
        assertNotNull(actual.currentDispatchId());
        assertEquals(4, actual.notificationId().version());
        assertEquals(4, actual.currentDispatchId().version());
        assertNotEquals(actual.notificationId(), actual.currentDispatchId());
    }

    @Test
    void generatesNewIdsForEachCreation() {
        Notification first = create(factory, null);
        Notification second = create(factory, null);

        Set<UUID> ids = new HashSet<>(List.of(
                first.notificationId(), first.currentDispatchId(),
                second.notificationId(), second.currentDispatchId()
        ));

        assertEquals(4, ids.size());
    }

    @Test
    void initializesProcessingMetadata() {
        Notification actual = create(factory, null);

        assertAll(
                () -> assertEquals(1, actual.generation()),
                () -> assertEquals(0, actual.retryCount()),
                () -> assertEquals(0L, actual.version())
        );
    }

    @Test
    void doesNotExposeRecipientOrPayloadInToString() {
        Notification actual = create(factory, null);
        String description = actual.toString();

        assertTrue(description.contains(actual.notificationId().toString()));
        assertFalse(description.contains(RECIPIENT));
        assertFalse(description.contains(TITLE));
        assertFalse(description.contains(CONTENT));
    }

    private static Notification create(
            NotificationFactory target, Instant scheduledAt
    ) {
        return target.create(
                CUSTOMER_ID, Channel.EMAIL, RECIPIENT,
                TITLE, CONTENT, scheduledAt
        );
    }

    private static final class AdvancingClock extends Clock {

        private final Instant first;
        private final ZoneId zone;
        private final AtomicInteger reads;

        private AdvancingClock(Instant first) {
            this(first, ZoneOffset.UTC, new AtomicInteger());
        }

        private AdvancingClock(
                Instant first, ZoneId zone, AtomicInteger reads
        ) {
            this.first = first;
            this.zone = zone;
            this.reads = reads;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new AdvancingClock(first, zone, reads);
        }

        @Override
        public Instant instant() {
            return first.plusSeconds(reads.getAndIncrement());
        }
    }

}