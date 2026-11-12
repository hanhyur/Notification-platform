package me.hanhyur.notify.api.notification.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationOutboxPublicationRepository {

    /**
     * 발행 가능 시각이 된 미발행 이벤트를 availableAt, eventId(UUID 문자열 기준) 순으로 조회합니다.
     * 두 정렬 기준은 모두 오름차순입니다.
     * limit은 1 이상이며, 이 조회는 이벤트의 선점권을 부여하지 않습니다.
     */
    List<NotificationOutboxEvent> findPending(Instant now, int limit);

    /**
     * 발행 가능 시각을 지난 미발행 이벤트에 최초 완료 시각을 기록합니다.
     * 실제 기록했으면 true, 미존재·이미 완료·아직 발행 불가이면 false입니다.
     */
    boolean markPublished(UUID eventId, Instant publishedAt);
}
