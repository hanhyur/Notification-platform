package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.NotificationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperatorNotificationHistory {

    Optional<IntegrationNotificationDetail> detail(UUID notificationId, UUID operatorId);

    boolean notificationExists(UUID notificationId);

    List<Attempt> attempts(UUID notificationId, Integer beforeAttemptNo, UUID beforeId, int limit);

    List<Action> actions(UUID notificationId, Instant beforeCreatedAt, UUID beforeId, int limit);

    record Attempt(UUID attemptId, UUID notificationId, UUID dispatchId, int attemptNo, String status,
                   String failureCategory, Instant startedAt, Instant finishedAt, String providerReference,
                   String errorCode, String errorSummary) {
        @Override
        public String toString() {
            return "Attempt[attemptId=" + attemptId + ", status=" + status + "]";
        }
    }

    record Action(UUID id, UUID operatorId, String action, String reason, String evidence, String resolution,
                  NotificationStatus previousStatus, NotificationStatus resultingStatus, int generation,
                  Instant createdAt) {
        @Override
        public String toString() {
            return "Action[id=" + id + ", action=" + action + "]";
        }
    }
}
