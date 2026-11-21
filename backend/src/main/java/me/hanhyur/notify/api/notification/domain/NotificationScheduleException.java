package me.hanhyur.notify.api.notification.domain;

public class NotificationScheduleException extends IllegalArgumentException {

    public NotificationScheduleException() {
        super("예약 시각은 현재 시각 이후부터 30일 이내여야 합니다.");
    }
}
