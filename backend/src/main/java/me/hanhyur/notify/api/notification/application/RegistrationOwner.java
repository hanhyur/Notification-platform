package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.NotificationStatus;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RegistrationOwner(Kind kind, String id) {

    public enum Kind { INTEGRATION, OPERATOR }

    public RegistrationOwner {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(id);
    }

    public static RegistrationOwner integration(String clientId) {
        return new RegistrationOwner(Kind.INTEGRATION, clientId);
    }

    public static RegistrationOwner operator(UUID operatorId) {
        return new RegistrationOwner(Kind.OPERATOR, operatorId.toString());
    }

    public List<String> allowedActions(NotificationStatus status) {
        if (kind == Kind.INTEGRATION) {
            return List.of();
        }
        return operatorActions(status);
    }

    static List<String> operatorActions(NotificationStatus status) {
        return switch (status) {
            case FAILED -> List.of("RETRY");
            case UNKNOWN -> List.of("RESOLVE_UNKNOWN");
            default -> List.of();
        };
    }
}
