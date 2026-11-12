package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxRepository;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import me.hanhyur.notify.api.notification.infrastructure.JpaNotificationOutboxRepository;
import me.hanhyur.notify.api.support.PostgresTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import({
        PostgresTestConfiguration.class,
        NotificationOutboxRegistrationIntegrationTest.OutboxTestConfiguration.class
})
class NotificationOutboxRegistrationIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T09:00:00.123456Z");

    @Autowired
    private NotificationRegistrationService service;

    @Autowired
    private FailingOutboxRepository outboxRepository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearRows() {
        outboxRepository.failAfterInsert = false;
        jdbc.sql("DELETE FROM notification_outbox").update();
        jdbc.sql("DELETE FROM notifications").update();
    }

    @Test
    void commitsNotificationAndImmediateOutboxTogether() {
        Notification notification = service.register(command(null));

        NotificationOutboxEvent event = findEvent(notification.notificationId());
        assertAll(
                () -> assertEquals(1L, countNotifications()),
                () -> assertEquals(1L, countEvents()),
                () -> assertEquals(notification.notificationId(), event.notificationId()),
                () -> assertEquals(notification.currentDispatchId(), event.dispatchId()),
                () -> assertEquals(notification.generation(), event.generation()),
                () -> assertEquals(NOW, event.occurredAt()),
                () -> assertEquals(NOW, event.availableAt()),
                () -> assertTrue(jdbc.sql("""
                        SELECT published_at IS NULL FROM notification_outbox
                        WHERE event_id = :id
                        """).param("id", event.eventId()).query(Boolean.class).single())
        );
    }

    @Test
    void storesScheduledAvailabilityWithoutChangingNotificationState() {
        Instant scheduledAt = NOW.plus(Duration.ofDays(30));

        Notification notification = service.register(command(scheduledAt));

        NotificationOutboxEvent event = findEvent(notification.notificationId());
        assertEquals(NotificationStatus.SCHEDULED, notification.status());
        assertEquals(scheduledAt, notification.scheduledAt());
        assertEquals(NOW, event.occurredAt());
        assertEquals(scheduledAt, event.availableAt());
        assertEquals(1L, countEvents());
    }

    @Test
    void rollsBackBothRowsWhenOutboxProcessingFailsAfterInsert() {
        outboxRepository.failAfterInsert = true;

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> service.register(command(null))
        );

        assertEquals("Outbox 저장 후 처리 실패", failure.getMessage());
        assertEquals(0L, countNotifications());
        assertEquals(0L, countEvents());
    }

    @Test
    void preservesExistingRowsWhenALaterRegistrationFails() {
        Notification existing = service.register(command(null));
        NotificationOutboxEvent existingEvent = findEvent(existing.notificationId());
        outboxRepository.failAfterInsert = true;

        assertThrows(IllegalStateException.class, () -> service.register(command(null)));

        assertEquals(1L, countNotifications());
        assertEquals(1L, countEvents());
        assertEquals(existingEvent, findEvent(existing.notificationId()));
    }

    @Test
    void rollsBackBothRowsWithTheCallerTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        UUID notificationId = transaction.execute(status -> {
            Notification notification = service.register(command(null));
            assertEquals(1L, countNotifications());
            assertEquals(1L, countEvents());
            status.setRollbackOnly();
            return notification.notificationId();
        });

        assertNotNull(notificationId);
        assertEquals(0L, countNotifications());
        assertEquals(0L, countEvents());
    }

    @Test
    void rejectsInvalidScheduleWithoutLeavingEitherRow() {
        assertThrows(IllegalArgumentException.class, () -> service.register(command(NOW)));

        assertEquals(0L, countNotifications());
        assertEquals(0L, countEvents());
    }

    private NotificationOutboxEvent findEvent(UUID notificationId) {
        return jdbc.sql("""
                SELECT event_id, notification_id, dispatch_id, generation, occurred_at, available_at
                FROM notification_outbox WHERE notification_id = :id
                """).param("id", notificationId).query((rs, rowNum) ->
                new NotificationOutboxEvent(
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("notification_id", UUID.class),
                        rs.getObject("dispatch_id", UUID.class),
                        rs.getInt("generation"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("available_at", OffsetDateTime.class).toInstant()
                )
        ).single();
    }

    private long countNotifications() {
        return jdbc.sql("SELECT count(*) FROM notifications").query(Long.class).single();
    }

    private long countEvents() {
        return jdbc.sql("SELECT count(*) FROM notification_outbox").query(Long.class).single();
    }

    private static RegisterNotificationCommand command(Instant scheduledAt) {
        return new RegisterNotificationCommand(
                "customer-1001", Channel.EMAIL, "sample@example.com",
                "예약 안내", "예약이 접수되었습니다.", scheduledAt
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OutboxTestConfiguration {

        @Bean
        @Primary
        Clock outboxTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        FailingOutboxRepository failingOutboxRepository(JpaNotificationOutboxRepository delegate) {
            return new FailingOutboxRepository(delegate);
        }
    }

    static class FailingOutboxRepository implements NotificationOutboxRepository {

        private final JpaNotificationOutboxRepository delegate;
        private boolean failAfterInsert;

        FailingOutboxRepository(JpaNotificationOutboxRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public void insert(NotificationOutboxEvent event) {
            delegate.insert(event);
            if (failAfterInsert) {
                failAfterInsert = false;
                throw new IllegalStateException("Outbox 저장 후 처리 실패");
            }
        }
    }
}
