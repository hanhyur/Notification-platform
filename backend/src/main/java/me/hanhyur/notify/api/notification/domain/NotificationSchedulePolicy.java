package me.hanhyur.notify.api.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class NotificationSchedulePolicy {

    private static final Duration MAX_SCHEDULE_DELAY = Duration.ofDays(30);

    public NotificationStatus initialStatus(Instant scheduledAt, Instant now) {
        Objects.requireNonNull(now, "현재 시각은 필수입니다.");

        // 예약 시각이 없으면 Queue -> 즉시 발송과 발송 완료는 어떻게 구분할 것인가
        if (scheduledAt == null) {
            return NotificationStatus.QUEUED;
        }

        Duration delay = Duration.between(now, scheduledAt);

        /* 예외 처리
        * 현재 또는 과거 시각의 예약
        * 최대 스케쥴 딜레이 초과
        * 위반 시 IllegalArgumentException 발생
        * */
        if (delay.compareTo(MAX_SCHEDULE_DELAY) > 0 || !delay.isPositive()) {
            throw new IllegalArgumentException();
        }

        return NotificationStatus.SCHEDULED;
    }
}
