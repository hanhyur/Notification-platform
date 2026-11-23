package me.hanhyur.notify.api.notification.web;

import me.hanhyur.notify.api.notification.application.NotificationQueryException;
import me.hanhyur.notify.api.notification.application.NotificationSearch;
import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import org.springframework.util.MultiValueMap;

import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

record OperatorQueryParameters(MultiValueMap<String, String> values) {

    private static final Pattern TIMESTAMP = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:[Zz]|[+-]\\d{2}:\\d{2})");

    String value(String name) {
        var supplied = values.get(name);
        if (supplied == null) {
            return null;
        }
        if (supplied.size() != 1 || supplied.getFirst() == null) {
            throw NotificationQueryException.malformed();
        }
        return supplied.getFirst();
    }

    int size() {
        String raw = value("size");
        if (raw == null) {
            return 20;
        }
        try {
            BigInteger size = new BigInteger(raw);
            if (size.signum() <= 0 || size.compareTo(BigInteger.valueOf(100)) > 0) {
                throw NotificationQueryException.invalid("size", "페이지 크기는 1에서 100 사이여야 합니다.");
            }
            return size.intValueExact();
        } catch (NumberFormatException e) {
            throw NotificationQueryException.malformed();
        }
    }

    Instant instant(String name) {
        String raw = value(name);
        if (raw == null) {
            return null;
        }
        if (!TIMESTAMP.matcher(raw).matches()) {
            throw NotificationQueryException.malformed();
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            throw NotificationQueryException.malformed();
        }
    }

    NotificationSearch search() {
        var view = enumValue(NotificationSearch.View.class, value("view"));
        String customerId = value("customerId");
        if (customerId != null && customerId.indexOf(0) >= 0) {
            throw NotificationQueryException.invalid("customerId", "고객 번호에 널 문자를 사용할 수 없습니다.");
        }
        if (customerId != null && (customerId.isEmpty() || customerId.codePointCount(0, customerId.length()) > 100)) {
            throw NotificationQueryException.invalid("customerId", "고객 번호는 1자에서 100자 사이여야 합니다.");
        }
        return new NotificationSearch(view == null ? NotificationSearch.View.HISTORY : view, customerId,
                enumValue(Channel.class, value("channel")), statuses(), instant("from"), instant("to"));
    }

    private Set<NotificationStatus> statuses() {
        String raw = value("status");
        if (raw == null) {
            return Set.of();
        }
        var statuses = EnumSet.noneOf(NotificationStatus.class);
        for (String name : raw.split(",", -1)) {
            if (!statuses.add(enumValue(NotificationStatus.class, name))) {
                throw NotificationQueryException.malformed();
            }
        }
        return statuses;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw NotificationQueryException.malformed();
        }
    }

    static UUID notificationId(String raw) {
        try {
            UUID id = UUID.fromString(raw);
            if (id.toString().equalsIgnoreCase(raw)) {
                return id;
            }
        } catch (IllegalArgumentException e) {
            throw NotificationQueryException.malformed();
        }
        throw NotificationQueryException.malformed();
    }
}
