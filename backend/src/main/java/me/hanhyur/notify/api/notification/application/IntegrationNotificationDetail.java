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
                maskRecipient(notification.channel(), notification.recipient()), notification.status(),
                notification.createdAt(), notification.updatedAt(), notification.scheduledAt(), nextAttemptAt,
                acceptedAt, completedAt, allowedActions, notification.title(), notification.content(),
                notification.currentDispatchId(), notification.generation(), notification.retryCount(),
                notification.version(), lastErrorCode, errorSummary);
    }

    private static String maskRecipient(Channel channel, String recipient) {
        String masked = switch (channel) {
            case EMAIL -> {
                int at = recipient.lastIndexOf('@');
                if (at < 1) {
                    yield "***";
                }
                int first = recipient.offsetByCodePoints(0, 1);
                String prefix = first < at ? recipient.substring(0, first) : "";
                yield prefix + "***" + recipient.substring(at);
            }
            case SMS -> "***" + recipient.substring(Math.max(0, recipient.length() - 4));
            case KAKAOTALK -> "***";
        };
        return masked.equals(recipient) ? "•••" : masked;
    }

    @Override
    public String toString() {
        return "IntegrationNotificationDetail[notificationId=" + notificationId + ", status=" + status + "]";
    }
}
