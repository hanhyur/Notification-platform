package me.hanhyur.notify.api.notification.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import me.hanhyur.notify.api.auth.OperatorSession;
import me.hanhyur.notify.api.common.web.ApiExceptionHandler;
import me.hanhyur.notify.api.notification.application.IntegrationNotificationDetail;
import me.hanhyur.notify.api.notification.application.NotificationQueryException;
import me.hanhyur.notify.api.notification.application.OperatorActionCommand;
import me.hanhyur.notify.api.notification.application.OperatorActionException;
import me.hanhyur.notify.api.notification.application.OperatorActionService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications/{notificationId}")
class OperatorNotificationActionController {

    private final OperatorActionService actions;
    private final OperatorAuditInput audit;

    OperatorNotificationActionController(OperatorActionService actions, OperatorAuditInput audit) {
        this.actions = actions;
        this.audit = audit;
    }

    @PostMapping(path = "/retries", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<IntegrationNotificationDetail> retry(@PathVariable String notificationId,
            @AuthenticationPrincipal OperatorSession operator, @Valid @RequestBody OperatorActionRequests.Retry body,
            HttpServletRequest request) {
        audit.validate("reason", body.reason(), request);
        return execute(HttpStatus.ACCEPTED, notificationId, operator, request, body.command());
    }

    @PostMapping(path = "/resolutions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<IntegrationNotificationDetail> resolve(@PathVariable String notificationId,
            @AuthenticationPrincipal OperatorSession operator, @Valid @RequestBody OperatorActionRequests.Resolve body,
            HttpServletRequest request) {
        audit.validate("evidence", body.evidence(), request);
        return execute(HttpStatus.OK, notificationId, operator, request, body.command());
    }

    private ResponseEntity<IntegrationNotificationDetail> execute(HttpStatus status, String notificationId,
            OperatorSession operator, HttpServletRequest request, OperatorActionCommand command) {
        var id = OperatorQueryParameters.notificationId(notificationId);
        var result = actions.execute(operator.operatorId(), id, IdempotencyKey.from(request), command);
        return ResponseEntity.status(status).header("Idempotent-Replayed", Boolean.toString(result.replayed()))
                .body(result.detail());
    }

    @ExceptionHandler(NotificationQueryException.class)
    ProblemDetail invalidNotificationId(WebRequest request) {
        return ApiExceptionHandler.problem(HttpStatus.BAD_REQUEST, "BadRequest", "알림 번호는 UUID 형식이어야 합니다.",
                "INVALID_REQUEST", Map.of(), request);
    }

    @ExceptionHandler(OperatorActionException.class)
    ProblemDetail invalidAction(OperatorActionException error, WebRequest request) {
        return switch (error.reason()) {
            case NOT_FOUND -> ApiExceptionHandler.problem(HttpStatus.NOT_FOUND, "NotFound", error.getMessage(),
                    error.reason().name(), Map.of(), request);
            case STATE_OR_VERSION_CONFLICT -> ApiExceptionHandler.problem(HttpStatus.CONFLICT, "Conflict",
                    error.getMessage(), error.reason().name(), Map.of(), request);
            case VALIDATION_FAILED -> ApiExceptionHandler.problem(HttpStatus.UNPROCESSABLE_CONTENT, "UnprocessableEntity",
                    error.getMessage(), error.reason().name(), Map.of(error.field(), List.of(error.getMessage())), request);
        };
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ProblemDetail unavailable(WebRequest request) {
        return ApiExceptionHandler.problem(HttpStatus.SERVICE_UNAVAILABLE, "ServiceUnavailable",
                "운영 조치 접수 결과를 확인할 수 없습니다. 같은 키로 다시 요청하세요.",
                "PERSISTENCE_UNAVAILABLE", Map.of(), request);
    }
}
