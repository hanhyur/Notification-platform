package me.hanhyur.notify.api.notification.web;

import jakarta.servlet.http.HttpServletRequest;
import me.hanhyur.notify.api.notification.application.OperatorActionException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
final class OperatorAuditInput {

    private static final Pattern EMAIL = Pattern.compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}");
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w+])(?:\\+[1-9][0-9 -]{6,18}[0-9]|0[0-9]{1,2}[- ][0-9]{3,4}[- ][0-9]{4}"
                    + "|010[0-9]{8}(?!\\w))(?![0-9])");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)\\b(?:bearer\\s+\\S+|(?:x-api-key|api[_-]?key|authorization|x-csrf-token|token|password|session)\\s*[:=]\\s*\\S+)");

    private final IntegrationClientAccess.Settings integration;

    OperatorAuditInput(IntegrationClientAccess.Settings integration) {
        this.integration = integration;
    }

    void validate(String field, String text, HttpServletRequest request) {
        if (text.indexOf(0) >= 0) {
            throw OperatorActionException.invalid(field, "감사 기록에 널 문자를 사용할 수 없습니다.");
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (EMAIL.matcher(text).find() || PHONE.matcher(text).find()
                || CREDENTIAL.matcher(text).find()
                || containsValue(text, request.getRequestedSessionId())
                || containsValue(text, request.getHeader("X-CSRF-TOKEN"))
                || containsValue(text, request.getHeader("X-API-Key"))
                || (authorization != null && containsValue(text, authorization.substring(authorization.indexOf(' ') + 1)))
                || (integration.clients() != null
                    && integration.clients().values().stream().anyMatch(key -> containsValue(text, key)))) {
            throw OperatorActionException.invalid(field, "감사 기록에는 수신 주소 또는 인증 정보를 입력할 수 없습니다.");
        }
    }

    private boolean containsValue(String text, String value) {
        if (value == null || value.isBlank() || !text.contains(value)) {
            return false;
        }
        if (value.codePointCount(0, value.length()) >= 8 || text.equals(value)) {
            return true;
        }
        String token = "(?<![\\p{L}\\p{N}])" + Pattern.quote(value) + "(?![\\p{L}\\p{N}])";
        return Pattern.compile(token).matcher(text).find();
    }
}
