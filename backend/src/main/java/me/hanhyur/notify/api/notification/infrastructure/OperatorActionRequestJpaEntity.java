package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import me.hanhyur.notify.api.notification.application.OperatorActionStore;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "operator_action_requests")
class OperatorActionRequestJpaEntity {

    @Embeddable
    record Key(
            @Column(name = "operator_id", nullable = false) UUID operatorId,
            @Column(name = "command_type", nullable = false, length = 16) String commandType,
            @Column(name = "idempotency_key", nullable = false) UUID idempotencyKey
    ) implements Serializable {}

    @EmbeddedId
    private Key key;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    protected OperatorActionRequestJpaEntity() {}

    OperatorActionStore.Receipt receipt() {
        return new OperatorActionStore.Receipt(requestHash, notificationId);
    }
}
