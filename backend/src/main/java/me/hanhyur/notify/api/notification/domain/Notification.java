package me.hanhyur.notify.api.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record Notification(
        UUID notificationId,
        UUID currentDispatchId,
        String customerId,
        Channel channel,
        String recipient,
        String title,
        String content,
        NotificationStatus status,
        Instant scheduledAt,
        Instant createdAt,
        Instant updatedAt,
        int generation,
        int retryCount,
        long version
) {
    @Override
    public String toString() {
        return "Notification[notificationId=" + notificationId
                + ", currentDispatchId=" + currentDispatchId
                + ", status=" + status + "]";
    }
}
