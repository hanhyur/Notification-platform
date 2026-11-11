package me.hanhyur.notify.api.notification.domain;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository {

    /**
     * 알림의 현재 값을 새 행으로 저장합니다.
     * 기존 식별자의 행을 갱신하거나 덮어쓰지 않습니다.
     */
    void insert(Notification notification);

    /**
     * 식별자가 일치하는 알림을 반환하며, 없으면 빈 Optional을 반환합니다.
     */
    Optional<Notification> findById(UUID notificationId);
}
