package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import me.hanhyur.notify.api.notification.application.IntegrationRegistrationRequests;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "integration_registration_requests")
class IntegrationRegistrationRequestJpaEntity {

    @Embeddable
    record Key(
            @Column(name = "client_id", length = 100) String clientId,
            @Column(name = "idempotency_key") UUID idempotencyKey
    ) implements Serializable {
    }

    @EmbeddedId
    private Key key;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "notification_id")
    private UUID notificationId;

    protected IntegrationRegistrationRequestJpaEntity() {
    }

    IntegrationRegistrationRequests.Receipt receipt() {
        return new IntegrationRegistrationRequests.Receipt(requestHash,
                Objects.requireNonNull(notificationId, "완료된 요청에는 알림 번호가 필요합니다."));
    }

    void complete(UUID notificationId) {
        this.notificationId = Objects.requireNonNull(notificationId);
    }
}
