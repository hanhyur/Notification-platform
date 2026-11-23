package me.hanhyur.notify.api.notification.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

final class IdempotencyKey {

    private IdempotencyKey() {}

    static UUID from(HttpServletRequest request) {
        String value = IntegrationClientAccess.singleHeader(request, "Idempotency-Key");
        if (value != null && value.length() == 36) {
            try {
                UUID parsed = UUID.fromString(value);
                if (parsed.toString().equalsIgnoreCase(value)) {
                    return parsed;
                }
            } catch (IllegalArgumentException ignored) {
                throw IntegrationProblem.INVALID_REQUEST.exception(request);
            }
        }
        throw IntegrationProblem.INVALID_REQUEST.exception(request);
    }
}
