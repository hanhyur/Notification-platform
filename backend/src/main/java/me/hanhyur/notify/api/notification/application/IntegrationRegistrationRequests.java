package me.hanhyur.notify.api.notification.application;

import java.util.Optional;
import java.util.UUID;

public interface IntegrationRegistrationRequests {

    Optional<Receipt> reserve(RegistrationOwner owner, UUID key, String requestHash);

    void complete(RegistrationOwner owner, UUID key, UUID notificationId);

    record Receipt(String requestHash, UUID notificationId) {
    }
}
