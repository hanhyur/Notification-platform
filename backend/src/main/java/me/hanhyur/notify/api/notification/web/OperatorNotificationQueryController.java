package me.hanhyur.notify.api.notification.web;

import me.hanhyur.notify.api.auth.OperatorSession;
import me.hanhyur.notify.api.common.web.ApiExceptionHandler;
import me.hanhyur.notify.api.notification.application.IntegrationNotificationDetail;
import me.hanhyur.notify.api.notification.application.NotificationQueryException;
import me.hanhyur.notify.api.notification.application.OperatorNotificationHistory;
import me.hanhyur.notify.api.notification.application.OperatorNotificationQueryService;
import me.hanhyur.notify.api.notification.application.OperatorOverviewQueries;
import me.hanhyur.notify.api.notification.application.QueryPage;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
class OperatorNotificationQueryController {

    private final OperatorNotificationQueryService queries;

    OperatorNotificationQueryController(OperatorNotificationQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/dashboard")
    OperatorOverviewQueries.Dashboard dashboard(@RequestParam MultiValueMap<String, String> parameters) {
        var query = new OperatorQueryParameters(parameters);
        return queries.dashboard(query.instant("from"), query.instant("to"));
    }

    @GetMapping("/notifications")
    QueryPage<OperatorOverviewQueries.NotificationSummary> notifications(@RequestParam MultiValueMap<String, String> parameters) {
        var query = new OperatorQueryParameters(parameters);
        return queries.notifications(query.search(), query.size(), query.value("cursor"));
    }

    @GetMapping("/notifications/{notificationId}")
    IntegrationNotificationDetail detail(@PathVariable String notificationId, @AuthenticationPrincipal OperatorSession operator) {
        return queries.detail(OperatorQueryParameters.notificationId(notificationId), operator.operatorId());
    }

    @GetMapping("/notifications/{notificationId}/attempts")
    QueryPage<OperatorNotificationHistory.Attempt> attempts(@PathVariable String notificationId,
                                                           @RequestParam MultiValueMap<String, String> parameters) {
        var query = new OperatorQueryParameters(parameters);
        return queries.attempts(OperatorQueryParameters.notificationId(notificationId), query.size(), query.value("cursor"));
    }

    @GetMapping("/notifications/{notificationId}/actions")
    QueryPage<OperatorNotificationHistory.Action> actions(@PathVariable String notificationId,
                                                         @RequestParam MultiValueMap<String, String> parameters) {
        var query = new OperatorQueryParameters(parameters);
        return queries.actions(OperatorQueryParameters.notificationId(notificationId), query.size(), query.value("cursor"));
    }

    @ExceptionHandler(NotificationQueryException.class)
    ProblemDetail invalidQuery(NotificationQueryException error, WebRequest request) {
        var status = switch (error.reason()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        String title = switch (error.reason()) {
            case INVALID_REQUEST -> "BadRequest";
            case VALIDATION_FAILED -> "UnprocessableEntity";
            case NOT_FOUND -> "NotFound";
        };
        Map<String, List<String>> fields = error.field() == null ? Map.of() : Map.of(error.field(), List.of(error.getMessage()));
        return ApiExceptionHandler.problem(status, title, error.getMessage(), error.reason().name(), fields, request);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ProblemDetail unavailable(WebRequest request) {
        return ApiExceptionHandler.problem(HttpStatus.SERVICE_UNAVAILABLE, "ServiceUnavailable", "알림 정보를 조회할 수 없습니다.",
                "PERSISTENCE_UNAVAILABLE", Map.of(), request);
    }
}
