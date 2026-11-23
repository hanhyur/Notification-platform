package me.hanhyur.notify.api.notification.application;

public final class OperatorActionException extends RuntimeException {

    public enum Reason { NOT_FOUND, STATE_OR_VERSION_CONFLICT, VALIDATION_FAILED }

    private final Reason reason;
    private final String field;

    private OperatorActionException(Reason reason, String field, String message) {
        super(message);
        this.reason = reason;
        this.field = field;
    }

    static OperatorActionException notFound() {
        return new OperatorActionException(Reason.NOT_FOUND, null, "알림을 찾을 수 없습니다.");
    }

    public static OperatorActionException conflict() {
        return new OperatorActionException(Reason.STATE_OR_VERSION_CONFLICT, null,
                "알림 상태나 버전이 바뀌었거나 같은 요청 키에 다른 명령이 기록되어 있습니다.");
    }

    public static OperatorActionException invalid(String field, String message) {
        return new OperatorActionException(Reason.VALIDATION_FAILED, field, message);
    }

    public Reason reason() {
        return reason;
    }

    public String field() {
        return field;
    }
}
