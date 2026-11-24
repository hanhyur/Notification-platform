package me.hanhyur.notify.api.notification.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class OperatorActionService {

    public record Result(IntegrationNotificationDetail detail, boolean replayed) {}

    private final OperatorActionStore actions;
    private final OperatorNotificationHistory history;
    private final Clock clock;

    OperatorActionService(OperatorActionStore actions, OperatorNotificationHistory history, Clock clock) {
        this.actions = actions;
        this.history = history;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result execute(UUID operatorId, UUID notificationId, UUID key, OperatorActionCommand command) {
        String kind = command.kind().name();
        String hash = RequestFingerprint.of(notificationId, command);
        var previous = actions.reserve(operatorId, kind, key, notificationId, hash);
        if (previous.isPresent()) {
            return replay(operatorId, hash, previous.get());
        }

        var notification = actions.lock(notificationId).orElseThrow(OperatorActionException::notFound);
        var resultingStatus = command.resultingStatus(notification);
        command.validateAuditText(notification);

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant at = now.isBefore(notification.updatedAt()) ? notification.updatedAt() : now;
        boolean retry = command.kind() == OperatorActionCommand.Kind.RETRY;
        if (retry) {
            actions.retry(notificationId, UUID.randomUUID(), at);
        } else {
            actions.resolve(notificationId, resultingStatus, at);
        }

        var detail = currentDetail(notificationId, operatorId);
        var audit = new OperatorNotificationHistory.Action(UUID.randomUUID(), operatorId, kind,
                retry ? command.note() : null, retry ? null : command.note(),
                retry ? null : command.resolution().name(), notification.status(), detail.status(), detail.generation(), at);
        actions.record(notificationId, audit);

        return new Result(detail, false);
    }

    private Result replay(UUID operatorId, String requestHash, OperatorActionStore.Receipt receipt) {
        if (receipt.requestHash().equals(requestHash)) {
            var detail = currentDetail(receipt.notificationId(), operatorId);

            return new Result(detail, true);
        }

        throw OperatorActionException.conflict();
    }

    private IntegrationNotificationDetail currentDetail(UUID notificationId, UUID operatorId) {
        return history.detail(notificationId, operatorId).orElseThrow(OperatorActionException::notFound);
    }
}
