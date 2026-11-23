package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public record NotificationSearch(
        View view,
        String customerId,
        Channel channel,
        Set<NotificationStatus> statuses,
        Instant from,
        Instant to
) {

    public enum View {HISTORY, OPEN}

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Set<NotificationStatus> OPEN_STATUSES =
            Collections.unmodifiableSet(EnumSet.of(
                    NotificationStatus.SCHEDULED, NotificationStatus.QUEUED, NotificationStatus.SENDING,
                    NotificationStatus.RETRY_WAIT, NotificationStatus.UNKNOWN)
            );

    public NotificationSearch {
        statuses = statuses.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(statuses));
    }

    OperatorOverviewQueries.Filter filter(Instant reference) {
        if (view == View.OPEN) {
            if (from != null || to != null) {
                throw NotificationQueryException.invalid("view", "미종결 조회에는 기간을 지정할 수 없습니다.");
            }

            if (!OPEN_STATUSES.containsAll(statuses)) {
                throw NotificationQueryException.invalid("status", "미종결 상태만 선택할 수 있습니다.");
            }

            Set<NotificationStatus> selectedStatuses = statuses.isEmpty() ? OPEN_STATUSES : statuses;

            return new OperatorOverviewQueries.Filter(null, null, customerId, channel, selectedStatuses);
        }

        OperatorOverviewQueries.Window historyWindow = historyWindow(reference);

        return new OperatorOverviewQueries.Filter(
                historyWindow.from(),
                historyWindow.to(),
                customerId,
                channel,
                statuses);
    }

    static OperatorOverviewQueries.Window dashboardWindow(Instant from, Instant to, Instant now) {
        if (from == null && to == null) {
            return new OperatorOverviewQueries.Window(now.minus(Duration.ofDays(7)), now);
        }

        requireWindow(from, to, now);
        if (Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
            throw NotificationQueryException.invalid("from", "대시보드 조회 기간은 최대 31일입니다.");
        }

        return new OperatorOverviewQueries.Window(from, to);
    }

    private OperatorOverviewQueries.Window historyWindow(Instant reference) {
        if (from == null && to == null) {
            return new OperatorOverviewQueries.Window(reference.atZone(SEOUL).minusMonths(3).toInstant(), reference);
        }
        requireWindow(from, to, reference);

        if (from.isBefore(to.atZone(SEOUL).minusMonths(3).toInstant())) {
            throw NotificationQueryException.invalid("from", "알림 이력 조회 기간은 서울 기준 최대 3개월입니다.");
        }

        return new OperatorOverviewQueries.Window(from, to);
    }

    private static void requireWindow(Instant from, Instant to, Instant reference) {
        if (from == null || to == null) {
            throw NotificationQueryException.invalid(from == null ? "from" : "to", "시작과 끝 시각을 함께 입력하세요.");
        }

        if (!from.isBefore(to) || to.isAfter(reference)) {
            throw NotificationQueryException.invalid("to", "끝 시각은 시작 이후이며 현재 시각을 넘을 수 없습니다.");
        }
    }
}
