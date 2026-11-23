package me.hanhyur.notify.api.notification.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import me.hanhyur.notify.api.auth.OperatorSession;
import me.hanhyur.notify.api.notification.application.IntegrationNotificationDetail;
import me.hanhyur.notify.api.notification.application.IntegrationRegistrationConflictException;
import me.hanhyur.notify.api.notification.application.IntegrationRegistrationResult;
import me.hanhyur.notify.api.notification.application.IntegrationRegistrationService;
import me.hanhyur.notify.api.notification.application.RegisterNotificationCommand;
import me.hanhyur.notify.api.notification.domain.NotificationScheduleException;
import me.hanhyur.notify.api.notification.dto.NotificationCreateRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
class NotificationRegistrationController {

    static final String INTEGRATION_PATH = "/integration/v1/notifications";

    private final IntegrationRegistrationService registration;

    NotificationRegistrationController(IntegrationRegistrationService registration) {
        this.registration = registration;
    }

    @PostMapping(INTEGRATION_PATH)
    ResponseEntity<IntegrationNotificationDetail> createIntegrationNotification(
            @RequestAttribute(IntegrationClientAccess.CLIENT_ID) String clientId,
            @Valid @RequestBody NotificationCreateRequest body,
            HttpServletRequest request
    ) {
        return accepted(registration.register(clientId, IdempotencyKey.from(request), command(body)));
    }

    @PostMapping(path = "/api/v1/notifications", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<IntegrationNotificationDetail> createOperatorNotification(
            @AuthenticationPrincipal OperatorSession operator,
            @Valid @RequestBody NotificationCreateRequest body,
            HttpServletRequest request
    ) {
        return accepted(registration.registerAsOperator(operator.operatorId(), IdempotencyKey.from(request), command(body)));
    }

    private RegisterNotificationCommand command(NotificationCreateRequest body) {
        return new RegisterNotificationCommand(
                body.customerId(), body.channel(), body.recipient(), body.title(), body.content(), body.scheduledAt());
    }

    private ResponseEntity<IntegrationNotificationDetail> accepted(IntegrationRegistrationResult result) {
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/notifications/" + result.detail().notificationId()))
                .header("Idempotent-Replayed", Boolean.toString(result.replayed()))
                .body(result.detail());
    }

    @ExceptionHandler(IntegrationRegistrationConflictException.class)
    ProblemDetail conflict(HttpServletRequest request) {
        return IntegrationProblem.STATE_OR_VERSION_CONFLICT.body(request);
    }

    @ExceptionHandler(NotificationScheduleException.class)
    ProblemDetail invalidSchedule(HttpServletRequest request) {
        return IntegrationProblem.VALIDATION_FAILED.body(request);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ProblemDetail unavailable(HttpServletRequest request) {
        return IntegrationProblem.PERSISTENCE_UNAVAILABLE.body(request);
    }
}
