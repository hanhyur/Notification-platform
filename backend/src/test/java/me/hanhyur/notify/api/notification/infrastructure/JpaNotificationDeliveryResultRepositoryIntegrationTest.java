package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationDeliveryResultRepository;
import me.hanhyur.notify.api.notification.domain.NotificationDispatchClaimRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublicationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxRepository;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import me.hanhyur.notify.api.notification.domain.ProviderResult;
import me.hanhyur.notify.api.notification.domain.ProviderResult.Outcome;
import me.hanhyur.notify.api.support.PostgresTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class JpaNotificationDeliveryResultRepositoryIntegrationTest {

    private static final Instant START = Instant.parse("2026-11-19T08:00:00Z");
    private static final Instant FINISH = START.plusSeconds(1);

    @Autowired private NotificationDeliveryResultRepository results;
    @Autowired private NotificationDispatchClaimRepository claims;
    @Autowired private NotificationRepository notifications;
    @Autowired private NotificationOutboxRepository writer;
    @Autowired private NotificationOutboxPublicationRepository publisher;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void clearRows() {
        jdbc.sql("delete from notification_delivery_attempts").update();
        jdbc.sql("delete from notification_outbox").update();
        jdbc.sql("delete from notifications").update();
    }

    @Test
    void recordsTheFirstReceiptTogetherWithItsReferenceAndCompletionTimes() {
        var event = registeredEvent();
        assertTrue(claims.tryClaim(event, START));
        var receipt = accepted(event);

        results.finish(event, receipt, FINISH);
        results.finish(event, new ProviderResult(Outcome.UNKNOWN, null), FINISH.plusSeconds(5));

        assertEquals(new NotificationRow("ACCEPTED", FINISH, FINISH, FINISH, null, null, 2), notification(event));
        assertEquals(new Attempt("ACCEPTED", FINISH, receipt.reference(), null, null, null), attempt(event));
        assertTrue(publisher.findPending(FINISH, 10).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "PERMANENT_REJECTION, FAILED, REJECTED, PERMANENT_CONFIRMED_NOT_ACCEPTED",
            "UNKNOWN, UNKNOWN, UNKNOWN, AMBIGUOUS"
    })
    void recordsPermanentAndAmbiguousResponsesWithoutSchedulingAnotherSend(
            Outcome outcome, String status, String attemptStatus, String category) {
        var event = registeredEvent();
        assertTrue(claims.tryClaim(event, START));
        results.finish(event, new ProviderResult(outcome, null), FINISH);

        var attempt = attempt(event);
        assertEquals(attemptStatus, attempt.status());
        assertEquals(FINISH, attempt.finished());
        assertNull(attempt.reference());
        assertEquals(category, attempt.category());
        assertEquals(category, attempt.code());
        assertNotNull(attempt.summary());
        assertEquals(new NotificationRow(status, FINISH, null, outcome == Outcome.UNKNOWN ? null : FINISH,
                category, attempt.summary(), 2), notification(event));
        assertTrue(publisher.findPending(FINISH.plus(Duration.ofDays(1)), 10).isEmpty());
    }

    @Test
    void schedulesOnlyThreeRetriesAtTheContractDelaysAndThenClosesTheFourthAttempt() {
        var event = registeredEvent();
        Instant claimedAt = START;
        var rejection = new ProviderResult(Outcome.TRANSIENT_REJECTION, null);
        for (int delay : List.of(1, 5, 15)) {
            assertTrue(claims.tryClaim(event, claimedAt));
            Instant finished = claimedAt.plusSeconds(1);
            results.finish(event, rejection, finished);
            results.finish(event, rejection, finished.plusSeconds(1));

            assertEquals("REJECTED", attempt(event).status());
            assertEquals("TRANSIENT_CONFIRMED_NOT_ACCEPTED", attempt(event).category());
            var row = notification(event);
            assertEquals("RETRY_WAIT", row.status());
            assertNull(row.completed());
            assertNull(row.accepted());
            Instant due = finished.plus(Duration.ofMinutes(delay));
            assertTrue(publisher.findPending(due.minusSeconds(1), 10).isEmpty());
            var pending = publisher.findPending(due, 10);
            assertEquals(1, pending.size());
            var next = pending.getFirst();
            assertEquals(event.generation(), next.generation());
            assertEquals(event.notificationId(), next.notificationId());
            assertNotEquals(event.dispatchId(), next.dispatchId());
            assertEquals(due, next.availableAt());
            assertTrue(publisher.markPublished(next.eventId(), due));
            event = next;
            claimedAt = due;
        }

        assertTrue(claims.tryClaim(event, claimedAt));
        var finished = claimedAt.plusSeconds(1);
        results.finish(event, rejection, finished);

        assertEquals("FAILED", notification(event).status());
        assertEquals(finished, notification(event).completed());
        assertEquals(3, notifications.findById(event.notificationId()).orElseThrow().retryCount());
        assertTrue(publisher.findPending(finished.plus(Duration.ofDays(1)), 10).isEmpty());
    }

    @Test
    void aSuccessfulRetryClearsTheLastErrorAndPreservesTheEarlierRejectedAttempt() {
        var first = registeredEvent();
        assertTrue(claims.tryClaim(first, START));
        results.finish(first, new ProviderResult(Outcome.TRANSIENT_REJECTION, null), FINISH);
        var rejected = attempt(first);
        Instant due = FINISH.plusSeconds(60);
        var retry = publisher.findPending(due, 1).getFirst();
        assertTrue(claims.tryClaim(retry, due));

        results.finish(retry, accepted(retry), due);

        assertEquals(new NotificationRow("ACCEPTED", due, due, due, null, null, 4), notification(first));
        assertEquals(rejected, attempt(first));
        assertEquals("ACCEPTED", attempt(retry).status());
    }

    @Test
    void requiresThePersistedEventAndTheCurrentlyStartedDispatch() {
        var event = registeredEvent();
        results.finish(event, accepted(event), FINISH);
        assertEquals("QUEUED", notification(event).status());
        assertTrue(claims.tryClaim(event, START));
        var other = registeredEvent();
        for (var stale : List.of(
                new NotificationOutboxEvent(UUID.randomUUID(), event.notificationId(), event.dispatchId(),
                        event.generation(), START, START),
                new NotificationOutboxEvent(other.eventId(), event.notificationId(), event.dispatchId(),
                        event.generation(), START, START),
                new NotificationOutboxEvent(event.eventId(), event.notificationId(), UUID.randomUUID(),
                        event.generation(), START, START),
                new NotificationOutboxEvent(event.eventId(), event.notificationId(), event.dispatchId(),
                        event.generation() + 1, START, START))) {
            results.finish(stale, accepted(stale), FINISH);
        }
        assertEquals("SENDING", notification(event).status());
        assertEquals("STARTED", attempt(event).status());

        jdbc.sql("delete from notification_delivery_attempts where dispatch_id = :id")
                .param("id", event.dispatchId()).update();
        results.finish(event, accepted(event), FINISH);
        assertEquals("SENDING", notification(event).status());
    }

    @Test
    void rollsBackTheNotificationAttemptAndRetryOutboxTogether() {
        var event = registeredEvent();
        assertTrue(claims.tryClaim(event, START));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            results.finish(event, new ProviderResult(Outcome.TRANSIENT_REJECTION, null), FINISH);
            assertEquals("RETRY_WAIT", notification(event).status());
            tx.setRollbackOnly();
        });

        assertEquals("STARTED", attempt(event).status());
        assertEquals("SENDING", notification(event).status());
        assertTrue(publisher.findPending(FINISH.plus(Duration.ofDays(1)), 10).isEmpty());
    }

    @Test
    void serializesConflictingResultsForOneDispatch() throws Exception {
        var event = registeredEvent();
        assertTrue(claims.tryClaim(event, START));
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var success = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                results.finish(event, accepted(event), FINISH);
                return null;
            });
            var failure = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                results.finish(event, new ProviderResult(Outcome.UNKNOWN, null), FINISH.plusSeconds(1));
                return null;
            });
            success.get(10, TimeUnit.SECONDS);
            failure.get(10, TimeUnit.SECONDS);
        }
        var notification = notification(event);
        var attempt = attempt(event);
        assertTrue(List.of("ACCEPTED", "UNKNOWN").contains(notification.status()));
        assertEquals(notification.status(), attempt.status());
        assertEquals(notification.updated(), attempt.finished());
        assertEquals(2, notification.version());
    }

    private NotificationOutboxEvent registeredEvent() {
        var notification = new Notification(UUID.randomUUID(), UUID.randomUUID(), "customer-1", Channel.EMAIL,
                "sample@example.com", "발송 안내", "예약 알림", NotificationStatus.QUEUED, null,
                START, START, 1, 0, 0);
        notifications.insert(notification);
        var event = NotificationOutboxEvent.forRegistration(notification);
        writer.insert(event);
        assertTrue(publisher.markPublished(event.eventId(), START));
        return event;
    }

    private static ProviderResult accepted(NotificationOutboxEvent event) {
        return new ProviderResult(Outcome.ACCEPTED, "sandbox-" + event.dispatchId());
    }

    private NotificationRow notification(NotificationOutboxEvent event) {
        return jdbc.sql("""
                        select status, updated_at, accepted_at, completed_at, last_error_code, error_summary, version
                        from notifications where notification_id = :id
                        """).param("id", event.notificationId()).query((r, i) ->
                        new NotificationRow(r.getString("status"), instant(r.getObject("updated_at", OffsetDateTime.class)),
                                instant(r.getObject("accepted_at", OffsetDateTime.class)),
                                instant(r.getObject("completed_at", OffsetDateTime.class)),
                                r.getString("last_error_code"), r.getString("error_summary"), r.getLong("version")))
                .single();
    }

    private Attempt attempt(NotificationOutboxEvent event) {
        return jdbc.sql("""
                        select status, finished_at, provider_reference, failure_category, error_code, error_summary
                        from notification_delivery_attempts where dispatch_id = :id
                        """).param("id", event.dispatchId()).query((r, i) ->
                        new Attempt(r.getString("status"), instant(r.getObject("finished_at", OffsetDateTime.class)),
                                r.getString("provider_reference"), r.getString("failure_category"),
                                r.getString("error_code"), r.getString("error_summary")))
                .single();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record NotificationRow(String status, Instant updated, Instant accepted, Instant completed,
                                   String errorCode, String summary, long version) {}

    private record Attempt(String status, Instant finished, String reference, String category, String code,
                           String summary) {}
}
