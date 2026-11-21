package me.hanhyur.notify.api.notification.web;

import jakarta.servlet.http.HttpServletRequest;
import me.hanhyur.notify.api.common.web.ApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.List;
import java.util.Map;

enum IntegrationProblem {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "BadRequest", "올바른 Idempotency-Key가 필요합니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Unauthorized", "세션 또는 API 키가 유효하지 않다."),
    STATE_OR_VERSION_CONFLICT(HttpStatus.CONFLICT, "Conflict", "같은 키가 다른 알림 등록 내용에 사용되었습니다."),
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "UnprocessableEntity", "예약 시각이 허용 범위를 벗어났습니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "TooManyRequests", "클라이언트별 요청 제한을 초과했다."),
    PERSISTENCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "ServiceUnavailable",
            "DB에 요청을 안전하게 기록할 수 없어 접수 여부를 확인할 수 없습니다. 같은 키로 다시 요청하세요.");

    private final HttpStatus status;
    private final String title;
    private final String detail;

    IntegrationProblem(HttpStatus status, String title, String detail) {
        this.status = status;
        this.title = title;
        this.detail = detail;
    }

    ProblemDetail body(HttpServletRequest request) {
        Map<String, List<String>> fieldErrors = this == VALIDATION_FAILED
                ? Map.of("scheduledAt", List.of(detail)) : Map.of();
        return ApiExceptionHandler.problem(status, title, detail, name(), fieldErrors, new ServletWebRequest(request));
    }

    ErrorResponseException exception(HttpServletRequest request) {
        return new ErrorResponseException(status, body(request), null);
    }
}
