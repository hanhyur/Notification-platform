package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.EntityManager;
import me.hanhyur.notify.api.notification.application.IntegrationRegistrationRequests;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class JpaIntegrationRegistrationRequests implements IntegrationRegistrationRequests {

    private final EntityManager entityManager;

    JpaIntegrationRegistrationRequests(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Receipt> reserve(String clientId, UUID key, String requestHash) {
        int inserted = entityManager.createNativeQuery("""
                        INSERT INTO integration_registration_requests (client_id, idempotency_key, request_hash)
                        VALUES (:clientId, :key, :requestHash)
                        ON CONFLICT (client_id, idempotency_key) DO NOTHING
                        """)
                .setParameter("clientId", clientId)
                .setParameter("key", key)
                .setParameter("requestHash", requestHash)
                .executeUpdate();
        if (inserted == 1) {
            return Optional.empty();
        }
        return Optional.of(find(clientId, key).receipt());
    }

    @Override
    public void complete(String clientId, UUID key, UUID notificationId) {
        find(clientId, key).complete(notificationId);
    }

    private IntegrationRegistrationRequestJpaEntity find(String clientId, UUID key) {
        return entityManager.find(IntegrationRegistrationRequestJpaEntity.class,
                new IntegrationRegistrationRequestJpaEntity.Key(clientId, key));
    }
}
