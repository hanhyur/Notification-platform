package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;

import java.util.Objects;
import java.util.regex.Pattern;

public record OperatorActionCommand(
        Kind kind,
        long expectedVersion,
        Resolution resolution,
        String note
) {
    public enum Kind { RETRY, RESOLVE_UNKNOWN }
    public enum Resolution { ACCEPTED, NOT_SENT }

    public OperatorActionCommand {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(note);

        if ((kind == Kind.RESOLVE_UNKNOWN) != (resolution != null)) {
            throw new IllegalArgumentException("결과 정리 명령에만 확인 결과가 필요합니다.");
        }
    }

    public static OperatorActionCommand retry(long expectedVersion, String reason) {
        return new OperatorActionCommand(Kind.RETRY, expectedVersion, null, reason);
    }

    public static OperatorActionCommand resolve(long expectedVersion, Resolution resolution, String evidence) {
        return new OperatorActionCommand(Kind.RESOLVE_UNKNOWN, expectedVersion, resolution, evidence);
    }

    NotificationStatus resultingStatus(Notification notification) {
        if (expectedVersion != notification.version()) {
            throw OperatorActionException.conflict();
        }

        if (kind == Kind.RETRY) {
            if (notification.status() != NotificationStatus.FAILED) {
                throw OperatorActionException.conflict();
            }

            return NotificationStatus.QUEUED;
        }

        if (notification.status() != NotificationStatus.UNKNOWN) {
            throw OperatorActionException.conflict();
        }

        return switch (resolution) {
            case ACCEPTED -> NotificationStatus.ACCEPTED;
            case NOT_SENT -> NotificationStatus.FAILED;
        };
    }

    void validateAuditText(Notification notification) {
        if (mentions(notification.recipient()) || mentions(notification.content())) {
            throw OperatorActionException.invalid(kind == Kind.RETRY ? "reason" : "evidence",
                    "조치 기록에서 수신 주소와 알림 본문을 빼 주세요.");
        }
    }

    private boolean mentions(String value) {
        if (value.codePointCount(0, value.length()) >= 4) {
            return note.contains(value);
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(value) + "(?![\\p{L}\\p{N}])")
                .matcher(note).find();
    }

    @Override
    public String toString() {
        return "OperatorActionCommand[kind=" + kind + ", expectedVersion=" + expectedVersion + "]";
    }
}
