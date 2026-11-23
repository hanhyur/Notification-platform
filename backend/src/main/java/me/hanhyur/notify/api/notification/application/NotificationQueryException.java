package me.hanhyur.notify.api.notification.application;

public final class NotificationQueryException extends RuntimeException {

    public enum Reason { INVALID_REQUEST, VALIDATION_FAILED, NOT_FOUND }

    private final Reason reason;
    private final String field;

    private NotificationQueryException(Reason reason, String field, String message) {
        super(message);
        this.reason = reason;
        this.field = field;
    }

    public static NotificationQueryException malformed() {
        return new NotificationQueryException(Reason.INVALID_REQUEST, null, "조회 요청 또는 커서 형식이 올바르지 않습니다.");
    }

    public static NotificationQueryException invalid(String field, String message) {
        return new NotificationQueryException(Reason.VALIDATION_FAILED, field, message);
    }

    public static NotificationQueryException notFound() {
        return new NotificationQueryException(Reason.NOT_FOUND, null, "알림을 찾을 수 없습니다.");
    }

    public Reason reason() {
        return reason;
    }

    public String field() {
        return field;
    }
}
