package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationFactory;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxRepository;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationSchedulePolicy;
import me.hanhyur.notify.api.support.PostgresTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class JpaNotificationOutboxRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T09:00:00.123456Z");

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearRows() {
        jdbc.sql("DELETE FROM notification_outbox").update();
        jdbc.sql("DELETE FROM notifications").update();
    }

    @Test
    void storesEveryReferenceAndStartsUnpublished() {
        NotificationOutboxEvent expected = event(seedNotification());

        outboxRepository.insert(expected);

        Map<String, Object> row = jdbc.sql("""
                SELECT * FROM notification_outbox WHERE event_id = :id
                """).param("id", expected.eventId()).query().singleRow();
        assertAll(
                () -> assertEquals(expected.eventId(), row.get("event_id")),
                () -> assertEquals(expected.notificationId(), row.get("notification_id")),
                () -> assertEquals(expected.dispatchId(), row.get("dispatch_id")),
                () -> assertEquals(expected.generation(), row.get("generation")),
                () -> assertEquals(expected.occurredAt(), instant(row.get("occurred_at"))),
                () -> assertEquals(expected.availableAt(), instant(row.get("available_at"))),
                () -> assertNull(row.get("published_at")),
                () -> assertEquals(1L, countEvents())
        );
    }

    @Test
    void rejectsDuplicateEventIdWithoutOverwriting() {
        NotificationOutboxEvent original = event(seedNotification());
        outboxRepository.insert(original);
        NotificationOutboxEvent duplicate = new NotificationOutboxEvent(
                original.eventId(), original.notificationId(), UUID.randomUUID(),
                original.generation(), original.occurredAt(), NOW.plusSeconds(120)
        );

        DataIntegrityViolationException failure = assertThrows(
                DataIntegrityViolationException.class,
                () -> outboxRepository.insert(duplicate)
        );

        assertEquals("23505", sqlCause(failure).getSQLState());
        assertEquals(1L, countEvents());
        assertEquals(original.dispatchId(), jdbc.sql(
                "SELECT dispatch_id FROM notification_outbox WHERE event_id = :id"
        ).param("id", original.eventId()).query(UUID.class).single());
    }

    @Test
    void rejectsSecondEventForTheSameDispatch() {
        NotificationOutboxEvent original = event(seedNotification());
        outboxRepository.insert(original);
        NotificationOutboxEvent duplicateDispatch = new NotificationOutboxEvent(
                UUID.randomUUID(), original.notificationId(), original.dispatchId(),
                original.generation(), original.occurredAt(), original.availableAt()
        );

        DataIntegrityViolationException failure = assertThrows(
                DataIntegrityViolationException.class,
                () -> outboxRepository.insert(duplicateDispatch)
        );

        assertEquals("23505", sqlCause(failure).getSQLState());
        assertEquals(1L, countEvents());
    }

    @Test
    void rejectsReferenceToMissingNotification() {
        NotificationOutboxEvent missingParent = new NotificationOutboxEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, NOW, NOW
        );

        DataIntegrityViolationException failure = assertThrows(
                DataIntegrityViolationException.class,
                () -> outboxRepository.insert(missingParent)
        );

        assertEquals("23503", sqlCause(failure).getSQLState());
        assertEquals(0L, countEvents());
    }

    @Test
    void rollsBackWithCallerTransaction() {
        NotificationOutboxEvent event = event(seedNotification());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            outboxRepository.insert(event);
            assertEquals(1L, countEvents());
            status.setRollbackOnly();
        });

        assertEquals(0L, countEvents());
        assertTrue(notificationRepository.findById(event.notificationId()).isPresent());
    }

    @Test
    void preventsDeletingTheReferencedNotification() {
        Notification notification = seedNotification();
        outboxRepository.insert(event(notification));

        DataIntegrityViolationException failure = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.sql("DELETE FROM notifications WHERE notification_id = :id")
                        .param("id", notification.notificationId()).update()
        );

        assertEquals("23503", sqlCause(failure).getSQLState());
        assertEquals(1L, countEvents());
        assertTrue(notificationRepository.findById(notification.notificationId()).isPresent());
    }

    @Test
    void rejectsNullEvent() {
        assertThrows(NullPointerException.class, () -> outboxRepository.insert(null));

        assertEquals(0L, countEvents());
    }

    private Notification seedNotification() {
        Notification notification = new NotificationFactory(
                Clock.fixed(NOW, ZoneOffset.UTC), new NotificationSchedulePolicy()
        ).create(
                "customer-1001", Channel.EMAIL, "sample@example.com",
                "예약 안내", "예약이 접수되었습니다.", null
        );
        notificationRepository.insert(notification);
        return notification;
    }

    private static NotificationOutboxEvent event(Notification notification) {
        return new NotificationOutboxEvent(
                UUID.randomUUID(), notification.notificationId(),
                notification.currentDispatchId(), notification.generation(),
                notification.createdAt(), NOW.plusSeconds(60)
        );
    }

    private long countEvents() {
        return jdbc.sql("SELECT count(*) FROM notification_outbox").query(Long.class).single();
    }

    private static Instant instant(Object value) {
        return ((Timestamp) value).toInstant();
    }

    private static SQLException sqlCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql;
            }
        }
        throw new AssertionError("SQL 원인을 찾지 못했습니다.", failure);
    }
}
