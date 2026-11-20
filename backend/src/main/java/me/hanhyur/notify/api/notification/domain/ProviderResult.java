package me.hanhyur.notify.api.notification.domain;

import java.util.Objects;
import java.util.regex.Pattern;

public record ProviderResult(Outcome outcome, String reference) {

    private static final Pattern REFERENCE = Pattern.compile(
            "sandbox-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public ProviderResult {
        Objects.requireNonNull(outcome);
        if (outcome == Outcome.ACCEPTED) {
            if (reference == null || !REFERENCE.matcher(reference).matches()) {
                throw new IllegalArgumentException("공급자의 접수 번호 형식이 올바르지 않습니다.");
            }
        } else if (reference != null) {
            throw new IllegalArgumentException("접수 확인 시에만 참조 번호를 기록할 수 있습니다.");
        }
    }

    public enum Outcome {
        ACCEPTED,
        PERMANENT_REJECTION,
        TRANSIENT_REJECTION,
        UNKNOWN
    }
}
