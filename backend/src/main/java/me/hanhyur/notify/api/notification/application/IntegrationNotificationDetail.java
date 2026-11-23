package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IntegrationNotificationDetail(
        UUID notificationId, String customerId, Channel channel, String recipientMasked,
        NotificationStatus status, Instant createdAt, Instant updatedAt, Instant scheduledAt,
        Instant nextAttemptAt, Instant acceptedAt, Instant completedAt, List<String> allowedActions,
        String title, String content, UUID currentDispatchId, int generation, int retryCount,
        long version, String lastErrorCode, String errorSummary
) {
    public static IntegrationNotificationDetail from(Notification notification, Instant nextAttemptAt,
            Instant acceptedAt, Instant completedAt, String lastErrorCode, String errorSummary, List<String> allowedActions) {
        return new IntegrationNotificationDetail(
                notification.notificationId(), notification.customerId(), notification.channel(),
                RecipientMasking.mask(notification.channel(), notification.recipient()), notification.status(),
                notification.createdAt(), notification.updatedAt(), notification.scheduledAt(), nextAttemptAt,
                acceptedAt, completedAt, allowedActions, notification.title(), notification.content(),
                notification.currentDispatchId(), notification.generation(), notification.retryCount(),
                notification.version(), lastErrorCode, errorSummary);
    }

    @Override
    public String toString() {
        return "IntegrationNotificationDetail[notificationId=" + notificationId + ", status=" + status + "]";
    }
}
