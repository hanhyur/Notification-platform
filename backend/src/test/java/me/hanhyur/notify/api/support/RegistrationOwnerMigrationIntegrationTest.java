package me.hanhyur.notify.api.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegistrationOwnerMigrationIntegrationTest {

    @Test
    void preservesTheKeyHashAndNotificationOfAnExistingExternalClient() {
        try (var postgres = new PostgreSQLContainer("postgres:18.6")) {
            postgres.start();
            var db = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = JdbcClient.create(db);
            Flyway.configure().dataSource(db).target("5").load().migrate();

            String client = "c".repeat(100);
            UUID key = UUID.randomUUID();
            UUID notificationId = UUID.randomUUID();
            String hash = "a".repeat(64);
            jdbc.sql("""
                    insert into notifications (notification_id, current_dispatch_id, customer_id, channel,
                        recipient, title, content, status, created_at, updated_at)
                    values (:id, gen_random_uuid(), 'customer', 'KAKAOTALK', 'recipient', '주문', '접수',
                        'QUEUED', current_timestamp, current_timestamp)
                    """).param("id", notificationId).update();
            jdbc.sql("""
                    insert into integration_registration_requests (client_id, idempotency_key, request_hash, notification_id)
                    values (:client, :key, :hash, :id)
                    """).param("client", client).param("key", key).param("hash", hash).param("id", notificationId).update();

            var migration = Flyway.configure().dataSource(db).target("6").load();
            assertEquals(1, migration.migrate().migrationsExecuted);
            var receipt = jdbc.sql("""
                    select notification_id from integration_registration_requests
                    where owner_type = 'INTEGRATION' and client_id = :client and idempotency_key = :key and request_hash = :hash
                    """).param("client", client).param("key", key).param("hash", hash);
            assertEquals(notificationId, receipt.query(UUID.class).single());
            Flyway.configure().dataSource(db).target("8").load().migrate();
            assertEquals(notificationId, receipt.query(UUID.class).single());
            assertEquals(0, jdbc.sql("""
                    insert into integration_registration_requests (owner_type, client_id, idempotency_key, request_hash)
                    values ('INTEGRATION', :client, :key, :hash)
                    on conflict (owner_type, client_id, idempotency_key) do nothing
                    """).param("client", client).param("key", key).param("hash", hash).update());
        }
    }
}
