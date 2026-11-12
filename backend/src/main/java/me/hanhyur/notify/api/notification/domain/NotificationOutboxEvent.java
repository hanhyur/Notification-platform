package me.hanhyur.notify.api.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record NotificationOutboxEvent(
        UUID eventId,
        UUID notificationId,
        UUID dispatchId,
        int generation,
        Instant occurredAt,
        Instant availableAt
) {
    public NotificationOutboxEvent {
        Objects.requireNonNull(eventId, "이벤트 식별자는 필수입니다.");
        Objects.requireNonNull(notificationId, "알림 식별자는 필수입니다.");
        Objects.requireNonNull(dispatchId, "발송 식별자는 필수입니다.");
        Objects.requireNonNull(occurredAt, "이벤트 생성 시각은 필수입니다.");
        Objects.requireNonNull(availableAt, "발행 가능 시각은 필수입니다.");

        if (generation < 1) {
            throw new IllegalArgumentException("발송 세대는 1 이상이어야 합니다.");
        }

        if (availableAt.isBefore(occurredAt)) {
            throw new IllegalArgumentException("발행 가능 시각은 이벤트 생성 시각보다 빠를 수 없습니다.");
        }
    }

    public static NotificationOutboxEvent forRegistration(Notification notification) {
        Objects.requireNonNull(notification, "알림은 필수입니다.");

        Instant availableAt = notification.scheduledAt() == null
                ? notification.createdAt() : notification.scheduledAt();

        return new NotificationOutboxEvent(
                UUID.randomUUID(),
                notification.notificationId(),
                notification.currentDispatchId(),
                notification.generation(),
                notification.createdAt(),
                availableAt
        );
    }
}
