package me.hanhyur.notify.api.notification.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.validation.NotificationCreateGroupSequenceProvider;
import org.hibernate.validator.constraints.CodePointLength;
import org.hibernate.validator.group.GroupSequenceProvider;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;

@GroupSequenceProvider(NotificationCreateGroupSequenceProvider.class)
public record NotificationCreateRequest(
        @NotBlank
        @CodePointLength(max = 100)
        String customerId,

        @NotNull
        Channel channel,

        @NotBlank
        @CodePointLength(max = 320)
        @Email(groups = EmailChecks.class)
        @Pattern(
                regexp = "^\\+[1-9][0-9]{7,14}$",
                groups = SmsChecks.class
        )
        @CodePointLength(max = 100, groups = KakaoChecks.class)
        String recipient,

        @NotBlank
        @CodePointLength(max = 100)
        String title,

        @NotBlank
        @CodePointLength(max = 2000)
        String content,

        Instant scheduledAt
) {
    private static final Set<String> FIELDS = Set.of("customerId", "channel", "recipient", "title", "content", "scheduledAt");
    private static final java.util.regex.Pattern TIMESTAMP = java.util.regex.Pattern.compile(
            "(?i)[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?(?:Z|[+-][0-9]{2}:[0-9]{2})");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static NotificationCreateRequest fromJson(JsonNode json) {
        if (!json.isObject() || !FIELDS.containsAll(json.propertyNames())) {
            throw new IllegalArgumentException("허용된 알림 필드가 들어 있는 JSON 객체가 필요합니다.");
        }
        String channel = text(json, "channel");
        String scheduledAt = text(json, "scheduledAt");
        if (scheduledAt != null && !TIMESTAMP.matcher(scheduledAt).matches()) {
            throw new IllegalArgumentException("예약 시각은 시간대가 포함된 RFC3339 문자열이어야 합니다.");
        }
        return new NotificationCreateRequest(
                text(json, "customerId"), channel == null ? null : Channel.valueOf(channel),
                text(json, "recipient"), text(json, "title"), text(json, "content"),
                scheduledAt == null ? null : OffsetDateTime.parse(scheduledAt).toInstant());
    }

    private static String text(JsonNode json, String field) {
        if (!json.hasNonNull(field)) {
            return null;
        }
        JsonNode value = json.get(field);
        if (!value.isString()) {
            throw new IllegalArgumentException("알림 필드의 JSON 타입이 올바르지 않습니다.");
        }
        return value.stringValue();
    }

    @Override
    public String toString() {
        return "NotificationCreateRequest[channel=" + channel + ", scheduledAt=" + scheduledAt + "]";
    }

    public interface EmailChecks {

    }

    public interface SmsChecks {

    }

    public interface KakaoChecks {

    }
}
