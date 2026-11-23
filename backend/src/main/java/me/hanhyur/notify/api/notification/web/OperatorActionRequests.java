package me.hanhyur.notify.api.notification.web;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import me.hanhyur.notify.api.notification.application.OperatorActionCommand;
import me.hanhyur.notify.api.notification.application.OperatorActionException;
import org.hibernate.validator.constraints.CodePointLength;
import tools.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.util.Set;

final class OperatorActionRequests {

    private OperatorActionRequests() {}

    record Retry(@NotNull @PositiveOrZero BigInteger expectedVersion,
                 @NotBlank @CodePointLength(min = 10, max = 500) String reason) {

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static Retry fromJson(JsonNode body) {
            requireFields(body, Set.of("expectedVersion", "reason"));
            return new Retry(version(body), text(body, "reason"));
        }

        OperatorActionCommand command() {
            return OperatorActionCommand.retry(storedVersion(expectedVersion), reason);
        }

        @Override
        public String toString() {
            return "Retry[expectedVersion=" + expectedVersion + "]";
        }
    }

    record Resolve(@NotNull @PositiveOrZero BigInteger expectedVersion,
                   @NotNull OperatorActionCommand.Resolution resolution,
                   @NotBlank @CodePointLength(min = 20, max = 1000) String evidence) {

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static Resolve fromJson(JsonNode body) {
            requireFields(body, Set.of("expectedVersion", "resolution", "evidence"));
            String value = text(body, "resolution");
            OperatorActionCommand.Resolution outcome = switch (value) {
                case null -> null;
                case "ACCEPTED" -> OperatorActionCommand.Resolution.ACCEPTED;
                case "NOT_SENT" -> OperatorActionCommand.Resolution.NOT_SENT;
                default -> throw new IllegalArgumentException("지원하지 않는 결과 정리 값입니다.");
            };
            return new Resolve(version(body), outcome, text(body, "evidence"));
        }

        OperatorActionCommand command() {
            return OperatorActionCommand.resolve(storedVersion(expectedVersion), resolution, evidence);
        }

        @Override
        public String toString() {
            return "Resolve[expectedVersion=" + expectedVersion + ", resolution=" + resolution + "]";
        }
    }

    private static void requireFields(JsonNode body, Set<String> names) {
        if (!body.isObject() || !names.containsAll(body.propertyNames())) {
            throw new IllegalArgumentException("허용된 조치 필드가 들어 있는 JSON 객체가 필요합니다.");
        }
    }

    private static String text(JsonNode body, String field) {
        if (!body.hasNonNull(field)) {
            return null;
        }
        JsonNode value = body.get(field);
        if (!value.isString()) {
            throw new IllegalArgumentException("조치 필드의 JSON 타입이 올바르지 않습니다.");
        }
        return value.stringValue();
    }

    private static BigInteger version(JsonNode body) {
        if (!body.hasNonNull("expectedVersion")) {
            return null;
        }
        var value = body.get("expectedVersion");
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("버전은 정수여야 합니다.");
        }
        return value.bigIntegerValue();
    }

    private static long storedVersion(BigInteger version) {
        if (version.bitLength() > 63) {
            throw OperatorActionException.conflict();
        }
        return version.longValueExact();
    }
}
