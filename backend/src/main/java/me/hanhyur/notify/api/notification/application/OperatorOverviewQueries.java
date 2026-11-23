package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface OperatorOverviewQueries {

    Dashboard dashboard(Instant from, Instant to, Instant now);

    List<NotificationSummary> notifications(Filter filter, Instant beforeCreatedAt, UUID beforeId, int limit);

    record Filter(
            Instant from,
            Instant to,
            String customerId,
            Channel channel,
            Set<NotificationStatus> statuses
    ) {
        public Filter {
            statuses = Set.copyOf(statuses);
        }
    }

    record Window(Instant from, Instant to) {
    }

    record Dashboard(Window window, Map<NotificationStatus, Long> countsByStatus, long totalCreated,
                     long pendingCount, long oldestDueLagSeconds, Instant generatedAt) {
    }

    record NotificationSummary(
            UUID notificationId, String customerId, Channel channel, String recipientMasked,
            NotificationStatus status, Instant createdAt, Instant updatedAt, Instant scheduledAt,
            Instant nextAttemptAt, Instant acceptedAt, Instant completedAt,
            List<String> allowedActions
    ) {
        public NotificationSummary(
                UUID notificationId, String customerId, Channel channel, String recipient,
                NotificationStatus status, Instant createdAt, Instant updatedAt, Instant scheduledAt,
                Instant dispatchAvailableAt, Instant acceptedAt, Instant completedAt
        ) {
            this(
                    notificationId,
                    customerId,
                    channel,
                    RecipientMasking.mask(channel, recipient),
                    status,
                    createdAt,
                    updatedAt,
                    scheduledAt,
                    status == NotificationStatus.RETRY_WAIT ? dispatchAvailableAt : null,
                    acceptedAt,
                    completedAt,
                    RegistrationOwner.operatorActions(status)
            );
        }
    }
}
