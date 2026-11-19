package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationDispatchClaimRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxRepository;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import me.hanhyur.notify.api.support.PostgresTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class JpaNotificationDispatchClaimRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-19T09:00:00.123456Z");
    private static final Instant CREATED_AT = NOW.minusSeconds(60);

    @Autowired
    private NotificationDispatchClaimRepository claims;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private NotificationOutboxRepository outbox;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearRows() {
        jdbc.sql("delete from notification_delivery_attempts").update();
        jdbc.sql("delete from notification_outbox").update();
        jdbc.sql("delete from notifications").update();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationStatus.class, names = {"QUEUED", "SCHEDULED", "RETRY_WAIT"})
    void claimsADueDispatchOnceEvenBeforeThePublisherRecordsItsAck(NotificationStatus status) {
        NotificationOutboxEvent event = seed(status, status == NotificationStatus.SCHEDULED ? NOW : null, NOW);

        assertTrue(claims.tryClaim(event, NOW));
        assertFalse(claims.tryClaim(event, NOW.plusSeconds(1)));

        Notification claimed = notifications.findById(event.notificationId()).orElseThrow();
        assertEquals(NotificationStatus.SENDING, claimed.status());
        assertEquals(1, claimed.version());
        assertEquals(NOW, claimed.updatedAt());
        assertEquals(List.of(new Attempt(event.dispatchId(), 1, "STARTED", NOW)), attempts(event.notificationId()));
    }

    @ParameterizedTest
    @EnumSource(value = NotificationStatus.class, names = {"SENDING", "ACCEPTED", "FAILED", "UNKNOWN"})
    void leavesIneligibleStatesUnchanged(NotificationStatus status) {
        NotificationOutboxEvent event = seed(status, null, NOW);

        assertFalse(claims.tryClaim(event, NOW));

        assertUnchanged(event, status);
    }

    @Test
    void refusesUnknownMismatchedAndStaleIdentifiers() {
        NotificationOutboxEvent current = seed(NotificationStatus.QUEUED, null, NOW);
        NotificationOutboxEvent other = seed(NotificationStatus.QUEUED, null, NOW);
        List<NotificationOutboxEvent> rejected = List.of(
                event(UUID.randomUUID(), current.notificationId(), current.dispatchId(), current.generation(), NOW),
                event(other.eventId(), current.notificationId(), current.dispatchId(), current.generation(), NOW),
                event(current.eventId(), other.notificationId(), current.dispatchId(), current.generation(), NOW),
                event(current.eventId(), current.notificationId(), other.dispatchId(), current.generation(), NOW),
                event(current.eventId(), current.notificationId(), current.dispatchId(), current.generation() + 1, NOW)
        );
        for (NotificationOutboxEvent supplied : rejected) {
            assertFalse(claims.tryClaim(supplied, NOW));
        }

        assertUnchanged(current, NotificationStatus.QUEUED);
        assertUnchanged(other, NotificationStatus.QUEUED);
    }

    @Test
    void rejectsAnEarlyEventUsingPersistedAvailabilityAndSchedule() {
        Instant later = NOW.plusSeconds(30);
        NotificationOutboxEvent waiting = seed(NotificationStatus.RETRY_WAIT, null, later);
        NotificationOutboxEvent forged = event(waiting.eventId(), waiting.notificationId(),
                waiting.dispatchId(), waiting.generation(), NOW);
        NotificationOutboxEvent scheduled = seed(NotificationStatus.SCHEDULED, later, NOW);

        assertFalse(claims.tryClaim(forged, NOW));
        assertFalse(claims.tryClaim(scheduled, NOW));
        assertUnchanged(waiting, NotificationStatus.RETRY_WAIT);
        assertUnchanged(scheduled, NotificationStatus.SCHEDULED);

        assertTrue(claims.tryClaim(waiting, later));
        assertTrue(claims.tryClaim(scheduled, later));
    }

    @Test
    void rejectsThePreviousDispatchAndIncrementsAttemptNumberAcrossGenerations() {
        NotificationOutboxEvent first = seed(NotificationStatus.QUEUED, null, NOW);
        assertTrue(claims.tryClaim(first, NOW));
        NotificationOutboxEvent next = event(UUID.randomUUID(), first.notificationId(),
                UUID.randomUUID(), first.generation() + 1, NOW);
        jdbc.sql("""
                        update notifications set current_dispatch_id = :dispatchId,
                            generation = :generation, status = 'QUEUED', retry_count = 0
                        where notification_id = :id
                        """)
                .param("dispatchId", next.dispatchId()).param("generation", next.generation())
                .param("id", next.notificationId()).update();
        outbox.insert(next);
        Instant nextTime = NOW.plusSeconds(1);

        assertFalse(claims.tryClaim(first, nextTime));
        assertTrue(claims.tryClaim(next, nextTime));
        assertEquals(List.of(
                new Attempt(first.dispatchId(), 1, "STARTED", NOW),
                new Attempt(next.dispatchId(), 2, "STARTED", nextTime)
        ), attempts(first.notificationId()));
    }

    @Test
    void continuesFromTheHighestAttemptNumberForThisNotification() {
        NotificationOutboxEvent current = seed(NotificationStatus.QUEUED, null, NOW);
        NotificationOutboxEvent other = seed(NotificationStatus.QUEUED, null, NOW);
        jdbc.sql("""
                        insert into notification_delivery_attempts
                            (attempt_id, notification_id, dispatch_id, attempt_no, status, started_at)
                        values (gen_random_uuid(), :current, gen_random_uuid(), 2, 'REJECTED', :startedAt),
                               (gen_random_uuid(), :current, gen_random_uuid(), 5, 'REJECTED', :startedAt),
                               (gen_random_uuid(), :other, gen_random_uuid(), 9, 'REJECTED', :startedAt)
                        """)
                .param("current", current.notificationId())
                .param("other", other.notificationId())
                .param("startedAt", CREATED_AT.atOffset(ZoneOffset.UTC))
                .update();

        assertTrue(claims.tryClaim(current, NOW));

        assertEquals(new Attempt(current.dispatchId(), 6, "STARTED", NOW),
                attempts(current.notificationId()).getLast());
    }

    @Test
    void onlyOneConcurrentWorkerStartsTheAttempt() throws Exception {
        NotificationOutboxEvent event = seed(NotificationStatus.QUEUED, null, NOW);
        CyclicBarrier start = new CyclicBarrier(2);
        Instant secondTime = NOW.plusSeconds(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return claims.tryClaim(event, NOW);
            });
            var second = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return claims.tryClaim(event, secondTime);
            });
            boolean firstWon = first.get(10, TimeUnit.SECONDS);
            assertNotEquals(firstWon, second.get(10, TimeUnit.SECONDS));
            Instant winnerTime = firstWon ? NOW : secondTime;
            Notification claimed = notifications.findById(event.notificationId()).orElseThrow();
            assertEquals(NotificationStatus.SENDING, claimed.status());
            assertEquals(1, claimed.version());
            assertEquals(winnerTime, claimed.updatedAt());
            assertEquals(List.of(new Attempt(event.dispatchId(), 1, "STARTED", winnerTime)),
                    attempts(event.notificationId()));
        }
    }

    @Test
    void rollsBackBothTheClaimAndTheAttemptWithItsCaller() {
        NotificationOutboxEvent event = seed(NotificationStatus.QUEUED, null, NOW);
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            assertTrue(claims.tryClaim(event, NOW));
            transaction.setRollbackOnly();
        });

        assertUnchanged(event, NotificationStatus.QUEUED);
        assertTrue(claims.tryClaim(event, NOW));
    }

    @Test
    void rollsBackTheNotificationUpdateIfTheAttemptCannotBeInserted() {
        NotificationOutboxEvent event = seed(NotificationStatus.QUEUED, null, NOW);
        jdbc.sql("""
                        insert into notification_delivery_attempts
                            (attempt_id, notification_id, dispatch_id, attempt_no, status, started_at)
                        values (:attemptId, :notificationId, :dispatchId, 1, 'STARTED', current_timestamp)
                        """).param("attemptId", UUID.randomUUID()).param("notificationId", event.notificationId())
                .param("dispatchId", event.dispatchId()).update();

        assertThrows(DataIntegrityViolationException.class, () -> claims.tryClaim(event, NOW));

        Notification unchanged = notifications.findById(event.notificationId()).orElseThrow();
        assertEquals(NotificationStatus.QUEUED, unchanged.status());
        assertEquals(0, unchanged.version());
        assertEquals(CREATED_AT, unchanged.updatedAt());
        assertEquals(1, attempts(event.notificationId()).size());
    }

    private NotificationOutboxEvent seed(NotificationStatus status, Instant scheduledAt, Instant availableAt) {
        UUID notificationId = UUID.randomUUID();
        UUID dispatchId = UUID.randomUUID();
        notifications.insert(new Notification(notificationId, dispatchId, "customer-1", Channel.EMAIL,
                "sample@example.com", "발송 안내", "테스트 알림", status, scheduledAt,
                CREATED_AT, CREATED_AT, 1, 0, 0));
        NotificationOutboxEvent event = event(UUID.randomUUID(), notificationId, dispatchId, 1, availableAt);
        outbox.insert(event);
        return event;
    }

    private NotificationOutboxEvent event(UUID eventId, UUID notificationId, UUID dispatchId,
                                          int generation, Instant availableAt) {
        return new NotificationOutboxEvent(eventId, notificationId, dispatchId, generation, CREATED_AT, availableAt);
    }

    private void assertUnchanged(NotificationOutboxEvent event, NotificationStatus status) {
        Notification current = notifications.findById(event.notificationId()).orElseThrow();
        assertEquals(status, current.status());
        assertEquals(0, current.version());
        assertEquals(CREATED_AT, current.updatedAt());
        assertTrue(attempts(event.notificationId()).isEmpty());
    }

    private List<Attempt> attempts(UUID notificationId) {
        return jdbc.sql("""
                        select dispatch_id, attempt_no, status, started_at
                        from notification_delivery_attempts
                        where notification_id = :id order by attempt_no
                        """).param("id", notificationId)
                .query((row, number) -> new Attempt(row.getObject("dispatch_id", UUID.class),
                        row.getInt("attempt_no"), row.getString("status"),
                        row.getObject("started_at", OffsetDateTime.class).toInstant())).list();
    }

    private record Attempt(UUID dispatchId, int number, String status, Instant startedAt) {}
}
