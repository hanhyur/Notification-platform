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
        return register(RegistrationOwner.integration(clientId), key, command);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IntegrationRegistrationResult registerAsOperator(UUID operatorId, UUID key, RegisterNotificationCommand command) {
        return register(RegistrationOwner.operator(operatorId), key, command);
    }

    private IntegrationRegistrationResult register(RegistrationOwner owner, UUID key, RegisterNotificationCommand command) {
        String requestHash = RequestFingerprint.of(command);
        var previous = requests.reserve(owner, key, requestHash);
        if (previous.isPresent()) {
            return replay(previous.get(), requestHash, owner);
        }

        UUID notificationId = registration.register(command).notificationId();
        requests.complete(owner, key, notificationId);

        IntegrationNotificationDetail detail = details.find(notificationId, owner);

        return new IntegrationRegistrationResult(
                detail,
                false
        );
    }

    private IntegrationRegistrationResult replay(IntegrationRegistrationRequests.Receipt previous, String requestHash,
            RegistrationOwner owner) {
        if (!previous.requestHash().equals(requestHash)) {
            throw new IntegrationRegistrationConflictException();
        }

        IntegrationNotificationDetail detail = details.find(previous.notificationId(), owner);

        return new IntegrationRegistrationResult(
                detail,
                true
        );
    }
}
