package me.hanhyur.notify.api.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class NotificationSchedulePolicy {

    private static final Duration MAX_SCHEDULE_DELAY = Duration.ofDays(30);

    public NotificationStatus initialStatus(Instant scheduledAt, Instant now) {
        Objects.requireNonNull(now, "현재 시각은 필수입니다.");

        if (scheduledAt == null) {
            return NotificationStatus.QUEUED;
        }

        Duration delay = Duration.between(now, scheduledAt);

        if (delay.compareTo(MAX_SCHEDULE_DELAY) > 0 || !delay.isPositive()) {
            throw new IllegalArgumentException();
        }

        return NotificationStatus.SCHEDULED;
    }
}
