package me.hanhyur.notify.api.notification.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class IntegrationRegistrationService {

    private final IntegrationRegistrationRequests requests;
    private final NotificationRegistrationService registration;
    private final IntegrationNotificationDetails details;

    public IntegrationRegistrationService(
            IntegrationRegistrationRequests requests,
            NotificationRegistrationService registration,
            IntegrationNotificationDetails details
    ) {
        this.requests = requests;
        this.registration = registration;
        this.details = details;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IntegrationRegistrationResult register(String clientId, UUID key, RegisterNotificationCommand command) {
        String requestHash = RegistrationFingerprint.of(command);
        var previous = requests.reserve(clientId, key, requestHash);
        if (previous.isPresent()) {
            return replay(previous.get(), requestHash);
        }

        UUID notificationId = registration.register(command).notificationId();
        requests.complete(clientId, key, notificationId);

        IntegrationNotificationDetail detail = details.find(notificationId);

        return new IntegrationRegistrationResult(
                detail,
                false
        );
    }

    private IntegrationRegistrationResult replay(IntegrationRegistrationRequests.Receipt previous, String requestHash) {
        if (!previous.requestHash().equals(requestHash)) {
            throw new IntegrationRegistrationConflictException();
        }

        IntegrationNotificationDetail detail = details.find(previous.notificationId());

        return new IntegrationRegistrationResult(
                detail,
                true
        );
    }
}
