package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class IntegrationRegistrationServiceTest {

    private static final String CLIENT = "order-service";
    private static final RegistrationOwner CLIENT_OWNER = RegistrationOwner.integration(CLIENT);
    private static final Instant SCHEDULED_AT = Instant.parse("2026-01-01T10:00:00Z");

    private final UUID key = UUID.randomUUID();
    private final UUID notificationId = UUID.randomUUID();
    private final IntegrationRegistrationRequests requests = mock(IntegrationRegistrationRequests.class);
    private final NotificationRegistrationService registration = mock(NotificationRegistrationService.class);
    private final IntegrationNotificationDetails details = mock(IntegrationNotificationDetails.class);
    private final IntegrationRegistrationService service = new IntegrationRegistrationService(requests, registration, details);

    @Test
    void replaysTheCurrentAcceptedDetailWithoutRegisteringAnExpiredScheduleAgain() {
        var command = command("주문 확인");
        String hash = RegistrationFingerprint.of(command);
        var previous = new IntegrationRegistrationRequests.Receipt(hash, notificationId);
        when(requests.reserve(CLIENT_OWNER, key, hash)).thenReturn(Optional.of(previous));
        Instant acceptedAt = SCHEDULED_AT.plusSeconds(2);
        var notification = new Notification(notificationId, UUID.randomUUID(), command.customerId(), command.channel(),
                command.recipient(), command.title(), command.content(), NotificationStatus.ACCEPTED,
                SCHEDULED_AT, SCHEDULED_AT.minusSeconds(60), acceptedAt, 1, 0, 2L);
        var current = IntegrationNotificationDetail.from(notification, null, acceptedAt, acceptedAt, null, null, List.of());
        when(details.find(notificationId, CLIENT_OWNER)).thenReturn(current);

        var result = service.register(CLIENT, key, command);

        assertEquals(new IntegrationRegistrationResult(current, true), result);
        verify(requests).reserve(CLIENT_OWNER, key, hash);
        verifyNoMoreInteractions(requests);
        verifyNoInteractions(registration);
    }

    @Test
    void rejectsConflictingContentWithoutLoadingOrRegisteringANotification() {
        var original = command("주문 확인");
        var changed = command("주문 취소");
        String changedHash = RegistrationFingerprint.of(changed);
        var previous = new IntegrationRegistrationRequests.Receipt(RegistrationFingerprint.of(original), notificationId);
        when(requests.reserve(CLIENT_OWNER, key, changedHash)).thenReturn(Optional.of(previous));

        assertThrows(IntegrationRegistrationConflictException.class,
                () -> service.register(CLIENT, key, changed));

        verify(requests).reserve(CLIENT_OWNER, key, changedHash);
        verifyNoMoreInteractions(requests);
        verifyNoInteractions(registration, details);
    }

    @Test
    void replaysTheRequestUsingTheAuthenticatedOperatorAsItsOwner() {
        UUID operatorId = UUID.randomUUID();
        var owner = RegistrationOwner.operator(operatorId);
        var command = command("주문 확인");
        String hash = RegistrationFingerprint.of(command);
        when(requests.reserve(owner, key, hash))
                .thenReturn(Optional.of(new IntegrationRegistrationRequests.Receipt(hash, notificationId)));
        var notification = new Notification(notificationId, UUID.randomUUID(), command.customerId(), command.channel(),
                command.recipient(), command.title(), command.content(), NotificationStatus.UNKNOWN,
                SCHEDULED_AT, SCHEDULED_AT.minusSeconds(60), SCHEDULED_AT, 1, 0, 2L);
        var current = IntegrationNotificationDetail.from(notification, null, null, null, null, null, List.of("RESOLVE_UNKNOWN"));
        when(details.find(notificationId, owner)).thenReturn(current);

        var result = service.registerAsOperator(operatorId, key, command);

        assertEquals(new IntegrationRegistrationResult(current, true), result);
        verifyNoInteractions(registration);
    }

    private RegisterNotificationCommand command(String title) {
        return new RegisterNotificationCommand("customer-15", Channel.EMAIL, "buyer@example.com", title,
                "주문 처리 결과입니다.", SCHEDULED_AT);
    }
}
