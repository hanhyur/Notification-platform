package me.hanhyur.notify.api.notification.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

class NotificationSchedulePolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-08T09:00:00Z");
    private final NotificationSchedulePolicy policy = new NotificationSchedulePolicy();

    @Test
    void returnQueuedWhenScheduledAtIsNull() {
        NotificationStatus actual = policy.initialStatus(null, NOW);

        assertEquals(NotificationStatus.QUEUED, actual);
    }

    @Test
    void returnScheduledForFutureTime() {
        Instant scheduledAt = NOW.plusSeconds(1);

        NotificationStatus actual = policy.initialStatus(scheduledAt, NOW);

        assertEquals(NotificationStatus.SCHEDULED, actual);
    }

    @Test
    void rejectsTimeEqualToNow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.initialStatus(NOW, NOW)
        );
    }

    @Test
    void rejectsPastTime() {
        Instant scheduledAt = NOW.minusSeconds(1);

        assertThrows(
                IllegalArgumentException.class,
                () -> policy.initialStatus(scheduledAt, NOW)
        );
    }

    @Test
    void acceptsTimeExactlyThirtyDaysLater() {
        Instant scheduledAt = NOW.plus(Duration.ofDays(30));

        NotificationStatus actual = policy.initialStatus(scheduledAt, NOW);

        assertEquals(NotificationStatus.SCHEDULED, actual);
    }

    @Test
    void rejectsTimeBeyondThirtyDays() {
        Instant scheduledAt = NOW.plus(Duration.ofDays(30)).plusNanos(1);

        assertThrows(
                IllegalArgumentException.class,
                () -> policy.initialStatus(scheduledAt, NOW)
        );
    }

    @Test
    void rejectsEquivalentCurrentTimeWithDifferentOffset() {
        Instant scheduledAt = OffsetDateTime.parse("2026-11-08T18:00:00+09:00").toInstant();

        assertThrows(
                IllegalArgumentException.class,
                () -> policy.initialStatus(scheduledAt, NOW)
        );
    }

    @Test
    void acceptsFutureTimeWithDifferentOffset() {
        Instant scheduledAt = OffsetDateTime.parse("2026-11-08T18:00:01+09:00").toInstant();

        NotificationStatus actual = policy.initialStatus(scheduledAt, NOW);

        assertEquals(NotificationStatus.SCHEDULED, actual);
    }

    @Test
    void requiresCurrentTime() {
        assertThrows(
                NullPointerException.class,
                () -> policy.initialStatus(null, null)
        );
    }
}