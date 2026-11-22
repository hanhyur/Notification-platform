package me.hanhyur.notify.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.hanhyur.notify.api.common.web.ApiExceptionHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.context.request.ServletWebRequest;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;

final class OperatorProblemWriter {

    enum Reason {
        UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Unauthorized", "세션 또는 인증 정보가 유효하지 않습니다."),
        FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden", "권한이 없거나 CSRF 토큰이 유효하지 않습니다."),
        RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "TooManyRequests", "요청 제한을 초과했습니다.");

        private final HttpStatus status;
        private final String title;
        private final String detail;

        Reason(HttpStatus status, String title, String detail) {
            this.status = status;
            this.title = title;
            this.detail = detail;
        }

        ProblemDetail body(HttpServletRequest request) {
            return ApiExceptionHandler.problem(status, title, detail, name(), Map.of(), new ServletWebRequest(request));
        }
    }

    private final JsonMapper json;

    OperatorProblemWriter(JsonMapper json) {
        this.json = json;
    }

    void write(HttpServletRequest request, HttpServletResponse response, Reason reason) throws IOException {
        response.setStatus(reason.status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        json.writeValue(response.getOutputStream(), reason.body(request));
    }
}
