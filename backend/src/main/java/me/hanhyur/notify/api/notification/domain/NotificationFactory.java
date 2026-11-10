package me.hanhyur.notify.api.notification.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class NotificationFactory {

    private final Clock clock;
    private final NotificationSchedulePolicy schedulePolicy;

    public NotificationFactory(
            Clock clock,
            NotificationSchedulePolicy schedulePolicy
    ) {
        this.clock = Objects.requireNonNull(clock, "시계는 필수입니다.");
        this.schedulePolicy = Objects.requireNonNull(
                schedulePolicy, "예약 정책은 필수입니다."
        );
    }

    public Notification create(
            String customerId,
            Channel channel,
            String recipient,
            String title,
            String content,
            Instant scheduledAt
    ) {
        Instant now = Instant.now(clock);

        NotificationStatus status = schedulePolicy.initialStatus(scheduledAt, now);

        UUID notificationId = UUID.randomUUID();
        UUID currentDispatchId = UUID.randomUUID();

        int generation = 1;
        int retryCount = 0;
        long version = 0L;

        return new Notification(
                notificationId,
                currentDispatchId,
                customerId,
                channel,
                recipient,
                title,
                content,
                status,
                scheduledAt,
                now,
                now,
                generation,
                retryCount,
                version
        );
    }
}
