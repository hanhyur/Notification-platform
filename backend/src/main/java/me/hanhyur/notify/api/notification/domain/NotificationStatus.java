package me.hanhyur.notify.api.notification.domain;

public enum NotificationStatus {
    SCHEDULED,
    QUEUED,
    SENDING,
    ACCEPTED,
    RETRY_WAIT,
    FAILED,
    UNKNOWN
}
