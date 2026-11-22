package me.hanhyur.notify.api.auth;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

public record OperatorSession(UUID operatorId, String displayName, List<String> roles) implements Serializable {
}
