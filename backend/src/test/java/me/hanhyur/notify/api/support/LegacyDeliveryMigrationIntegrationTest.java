package me.hanhyur.notify.api.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyDeliveryMigrationIntegrationTest {

    @Test
    void marksOnlyOutstandingCurrentClaimsUnknownWhenUpgradingFromV3() {
        try (var postgres = new PostgreSQLContainer("postgres:18.6")) {
            postgres.start();
            var db = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = JdbcClient.create(db);
            Flyway.configure().dataSource(db).target("3").load().migrate();
            insertLegacyRows(jdbc);
            var otherNotifications = jdbc.sql("""
                    select notification_id, status, current_dispatch_id, updated_at, version
                    from notifications where customer_id not like 'open-%' order by notification_id
                    """);
            var otherAttempts = jdbc.sql("""
                    select a.attempt_id, a.status, a.started_at from notification_delivery_attempts a
                    join notifications n using (notification_id)
                    where n.customer_id not like 'open-%' order by a.attempt_id
                    """);
            var outbox = jdbc.sql("select * from notification_outbox order by event_id");
            var originalNotifications = otherNotifications.query().listOfRows();
            var originalAttempts = otherAttempts.query().listOfRows();
            var publishedEvents = outbox.query().listOfRows();

            var v4 = Flyway.configure().dataSource(db).target("4").load();
            assertEquals(1, v4.migrate().migrationsExecuted);
            assertEquals(2, jdbc.sql("""
                    select count(*) from notifications n join notification_delivery_attempts a using (notification_id)
                    where n.customer_id like 'open-%' and n.status = 'UNKNOWN' and n.version = 8
                      and a.dispatch_id = n.current_dispatch_id and a.status = 'UNKNOWN'
                      and n.last_error_code = 'AMBIGUOUS' and a.failure_category = 'AMBIGUOUS'
                      and a.error_code = n.last_error_code and a.error_summary = n.error_summary
                      and a.error_summary = '공급자가 요청을 접수했는지 확인할 수 없습니다.'
                      and n.updated_at = a.finished_at and a.finished_at >= a.started_at
                      and n.updated_at >= n.created_at + interval '1 day'
                      and n.completed_at is null and n.accepted_at is null and a.provider_reference is null
                    """).query(Integer.class).single());
            assertEquals(originalNotifications, otherNotifications.query().listOfRows());
            assertEquals(originalAttempts, otherAttempts.query().listOfRows());
            assertEquals(publishedEvents, outbox.query().listOfRows());
            assertEquals(0, v4.migrate().migrationsExecuted);
        }
    }

    private void insertLegacyRows(JdbcClient jdbc) {
        jdbc.sql("""
                insert into notifications
                    (notification_id, current_dispatch_id, customer_id, channel, recipient, title, content,
                     status, created_at, updated_at, version)
                select gen_random_uuid(), gen_random_uuid(), customer, 'EMAIL', 'sample@example.com',
                       '알림', '본문', initial_status, current_timestamp, current_timestamp + interval '1 day', 7
                from (values ('open-start-later', 'SENDING'), ('open-update-later', 'SENDING'),
                             ('queued', 'QUEUED'), ('terminal', 'ACCEPTED'), ('stale-dispatch', 'SENDING'),
                             ('settled-attempt', 'SENDING'), ('missing-attempt', 'SENDING')) as cases(customer, initial_status)
                """).update();
        jdbc.sql("""
                insert into notification_delivery_attempts (attempt_id, notification_id, dispatch_id, attempt_no, status, started_at)
                select gen_random_uuid(), notification_id,
                       case when customer_id = 'stale-dispatch' then gen_random_uuid() else current_dispatch_id end,
                       1, case when customer_id = 'settled-attempt' then 'REJECTED' else 'STARTED' end,
                       case when customer_id = 'open-start-later' then updated_at + interval '1 day' else created_at end
                from notifications where customer_id <> 'missing-attempt'
                """).update();
        jdbc.sql("""
                insert into notification_outbox
                    (event_id, notification_id, dispatch_id, generation, occurred_at, available_at, published_at)
                select gen_random_uuid(), notification_id, current_dispatch_id, generation, created_at, created_at, created_at
                from notifications
                """).update();
    }
}
