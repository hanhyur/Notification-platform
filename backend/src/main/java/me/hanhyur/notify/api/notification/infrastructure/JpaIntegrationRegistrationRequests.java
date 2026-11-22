package me.hanhyur.notify.api.notification.infrastructure;

import jakarta.persistence.EntityManager;
import me.hanhyur.notify.api.notification.application.IntegrationRegistrationRequests;
import me.hanhyur.notify.api.notification.application.RegistrationOwner;
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
    public Optional<Receipt> reserve(RegistrationOwner owner, UUID key, String requestHash) {
        int inserted = entityManager.createNativeQuery("""
                        INSERT INTO integration_registration_requests (owner_type, client_id, idempotency_key, request_hash)
                        VALUES (:ownerType, :ownerId, :key, :requestHash)
                        ON CONFLICT (owner_type, client_id, idempotency_key) DO NOTHING
                        """)
                .setParameter("ownerType", owner.kind().name())
                .setParameter("ownerId", owner.id())
                .setParameter("key", key)
                .setParameter("requestHash", requestHash)
                .executeUpdate();
        if (inserted == 1) {
            return Optional.empty();
        }
        return Optional.of(find(owner, key).receipt());
    }

    @Override
    public void complete(RegistrationOwner owner, UUID key, UUID notificationId) {
        find(owner, key).complete(notificationId);
    }

    private IntegrationRegistrationRequestJpaEntity find(RegistrationOwner owner, UUID key) {
        return entityManager.find(IntegrationRegistrationRequestJpaEntity.class,
                new IntegrationRegistrationRequestJpaEntity.Key(owner.kind().name(), owner.id(), key));
    }
}
