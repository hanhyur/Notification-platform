package me.hanhyur.notify.api.notification.domain;

import java.time.Duration;
import java.util.List;

public record DeliveryDecision(NotificationStatus status, Failure failure, Duration retryDelay) {

    private static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));

    public enum Failure {
        TRANSIENT_CONFIRMED_NOT_ACCEPTED("공급자가 일시적으로 요청을 접수하지 않았습니다."),
        PERMANENT_CONFIRMED_NOT_ACCEPTED("공급자가 요청을 거절했습니다."),
        AMBIGUOUS("공급자가 요청을 접수했는지 확인할 수 없습니다.");

        private final String summary;

        Failure(String summary) {
            this.summary = summary;
        }
    }

    public static DeliveryDecision decide(ProviderResult.Outcome outcome, int retryCount) {
        if (retryCount < 0 || retryCount > RETRY_DELAYS.size()) {
            throw new IllegalArgumentException("현재 주기의 재시도 횟수가 올바르지 않습니다.");
        }

        return switch (outcome) {
            case ACCEPTED -> new DeliveryDecision(
                    NotificationStatus.ACCEPTED, null, null
            );

            case PERMANENT_REJECTION -> new DeliveryDecision(
                    NotificationStatus.FAILED, Failure.PERMANENT_CONFIRMED_NOT_ACCEPTED, null
            );

            case UNKNOWN -> new DeliveryDecision(
                    NotificationStatus.UNKNOWN, Failure.AMBIGUOUS, null
            );

            case TRANSIENT_REJECTION -> {
                if (retryCount == RETRY_DELAYS.size()) {
                    yield new DeliveryDecision(
                            NotificationStatus.FAILED, Failure.TRANSIENT_CONFIRMED_NOT_ACCEPTED, null
                    );
                }

                Duration delay = RETRY_DELAYS.get(retryCount);
                yield new DeliveryDecision(
                        NotificationStatus.RETRY_WAIT, Failure.TRANSIENT_CONFIRMED_NOT_ACCEPTED, delay
                );
            }
        };
    }

    public String attemptStatus() {
        if (failure == null) {
            return "ACCEPTED";
        }
        return failure == Failure.AMBIGUOUS ? "UNKNOWN" : "REJECTED";
    }

    public String errorCode() {
        return failure == null ? null : failure.name();
    }

    public String errorSummary() {
        return failure == null ? null : failure.summary;
    }
}
