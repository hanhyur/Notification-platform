package me.hanhyur.notify.api.notification.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class OperatorNotificationQueryService {

    private final OperatorOverviewQueries overview;
    private final OperatorNotificationHistory history;
    private final QueryCursors cursors;
    private final Clock clock;

    OperatorNotificationQueryService(OperatorOverviewQueries overview, OperatorNotificationHistory history,
                                     QueryCursors cursors, Clock clock) {
        this.overview = overview;
        this.history = history;
        this.cursors = cursors;
        this.clock = clock;
    }

    public OperatorOverviewQueries.Dashboard dashboard(Instant from, Instant to) {
        Instant now = clock.instant();
        var window = NotificationSearch.dashboardWindow(from, to, now);
        return overview.dashboard(window.from(), window.to(), now);
    }

    public QueryPage<OperatorOverviewQueries.NotificationSummary> notifications(NotificationSearch search,
                                                                               int size, String token) {
        var scope = cursors.scope("notifications", search);
        var after = cursors.decode(scope, token);
        Instant reference = after == null ? clock.instant() : after.reference();
        var rows = overview.notifications(search.filter(reference), after == null ? null : after.time(),
                after == null ? null : after.id(), size + 1);
        return QueryPage.from(rows, size, row -> cursors.encode(scope,
                new QueryCursors.Position(row.createdAt(), null, row.notificationId(), reference)));
    }

    public IntegrationNotificationDetail detail(UUID notificationId, UUID operatorId) {
        return history.detail(notificationId, operatorId).orElseThrow(NotificationQueryException::notFound);
    }

    public QueryPage<OperatorNotificationHistory.Attempt> attempts(UUID notificationId, int size, String token) {
        var scope = cursors.scope("attempts", notificationId);
        var after = cursors.decode(scope, token);
        requireNotification(notificationId);
        var rows = history.attempts(notificationId, after == null ? null : after.attemptNo(),
                after == null ? null : after.id(), size + 1);
        return QueryPage.from(rows, size, row -> cursors.encode(scope,
                new QueryCursors.Position(null, row.attemptNo(), row.attemptId(), null)));
    }

    public QueryPage<OperatorNotificationHistory.Action> actions(UUID notificationId, int size, String token) {
        var scope = cursors.scope("actions", notificationId);
        var after = cursors.decode(scope, token);
        requireNotification(notificationId);
        var rows = history.actions(notificationId, after == null ? null : after.time(),
                after == null ? null : after.id(), size + 1);
        return QueryPage.from(rows, size, row -> cursors.encode(scope,
                new QueryCursors.Position(row.createdAt(), null, row.id(), null)));
    }

    private void requireNotification(UUID notificationId) {
        if (!history.notificationExists(notificationId)) {
            throw NotificationQueryException.notFound();
        }
    }
}
