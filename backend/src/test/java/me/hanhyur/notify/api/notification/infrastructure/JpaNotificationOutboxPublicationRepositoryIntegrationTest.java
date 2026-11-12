package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationFactory;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublicationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxRepository;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationSchedulePolicy;
import me.hanhyur.notify.api.support.PostgresTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class JpaNotificationOutboxPublicationRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-12T09:00:00.123456Z");
    private static final Instant CREATED_AT = NOW.minusSeconds(120);

    @Autowired
    private NotificationOutboxPublicationRepository publicationRepository;

    @Autowired
    private NotificationOutboxRepository writer;

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
    void findsOnlyDueAndUnpublishedEventsIncludingTheExactBoundary() {
        NotificationOutboxEvent past = seedEvent(NOW.minusSeconds(1));
        NotificationOutboxEvent boundary = seedEvent(NOW);
        seedEvent(NOW.plusSeconds(1));
        seedPublishedEvent(NOW.minusSeconds(3), NOW.minusSeconds(2));

        List<NotificationOutboxEvent> actual = publicationRepository.findPending(NOW, 10);

        assertEquals(List.of(past, boundary), actual);
        assertNull(publishedAt(past.eventId()));
        assertNull(publishedAt(boundary.eventId()));
    }

    @Test
    void ordersByAvailabilityThenUuidTextOrder() {
        NotificationOutboxEvent last = seedEvent(new UUID(Long.MIN_VALUE, 1), NOW);
        NotificationOutboxEvent middle = seedEvent(new UUID(Long.MAX_VALUE, 0), NOW);
        NotificationOutboxEvent firstAtBoundary = seedEvent(new UUID(0, 1), NOW);
        NotificationOutboxEvent earlier =
                seedEvent(new UUID(Long.MIN_VALUE, 2), NOW.minusSeconds(1));

        List<NotificationOutboxEvent> actual = publicationRepository.findPending(NOW, 10);

        assertEquals(List.of(earlier, firstAtBoundary, middle, last), actual);
    }

    @Test
    void appliesLimitAfterFilteringAndOrdering() {
        seedPublishedEvent(NOW.minusSeconds(60), NOW.minusSeconds(30));
        seedEvent(NOW.plusSeconds(1));
        NotificationOutboxEvent earliest = seedEvent(NOW.minusSeconds(10));
        seedEvent(NOW.minusSeconds(5));

        List<NotificationOutboxEvent> actual = publicationRepository.findPending(NOW, 1);

        assertEquals(List.of(earliest), actual);
    }

    @Test
    void returnsEmptyWhenNoEventsExist() {
        assertTrue(publicationRepository.findPending(NOW, 10).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveLimit(int limit) {
        InvalidDataAccessApiUsageException failure = assertThrows(
                InvalidDataAccessApiUsageException.class,
                () -> publicationRepository.findPending(NOW, limit)
        );
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    @Test
    void rejectsNullQueryTime() {
        assertThrows(NullPointerException.class, () -> publicationRepository.findPending(null, 1));
    }

    @Test
    void recordsCompletionAtTheAvailabilityBoundaryWithoutChangingNotification() {
        NotificationOutboxEvent event = seedEvent(NOW);
        Notification original = notificationRepository.findById(event.notificationId()).orElseThrow();

        boolean updated = publicationRepository.markPublished(event.eventId(), NOW);

        assertTrue(updated);
        assertEquals(NOW, publishedAt(event.eventId()));
        assertTrue(publicationRepository.findPending(NOW.plusSeconds(1), 10).isEmpty());
        assertEquals(original, notificationRepository.findById(event.notificationId()).orElseThrow());
    }

    @Test
    void doesNotOverwriteTheFirstCompletionTime() {
        NotificationOutboxEvent event = seedEvent(NOW);

        assertTrue(publicationRepository.markPublished(event.eventId(), NOW));
        assertFalse(publicationRepository.markPublished(event.eventId(), NOW.plusSeconds(1)));

        assertEquals(NOW, publishedAt(event.eventId()));
    }

    @Test
    void returnsFalseForMissingEventWithoutChangingOtherRows() {
        NotificationOutboxEvent existing = seedEvent(NOW);

        assertFalse(publicationRepository.markPublished(UUID.randomUUID(), NOW));

        assertNull(publishedAt(existing.eventId()));
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM notification_outbox")
                .query(Long.class).single());
    }

    @Test
    void doesNotCompleteAnEventBeforeItBecomesAvailable() {
        NotificationOutboxEvent future = seedEvent(NOW.plusSeconds(1));

        assertFalse(publicationRepository.markPublished(future.eventId(), NOW));

        assertNull(publishedAt(future.eventId()));
        assertEquals(List.of(future), publicationRepository.findPending(NOW.plusSeconds(1), 10));
    }

    @Test
    void rejectsNullEventId() {
        assertThrows(
                NullPointerException.class,
                () -> publicationRepository.markPublished(null, NOW)
        );
    }

    @Test
    void rejectsNullCompletionTime() {
        assertThrows(
                NullPointerException.class,
                () -> publicationRepository.markPublished(UUID.randomUUID(), null)
        );
    }

    @Test
    void rollsBackCompletionWithTheCallerTransaction() {
        NotificationOutboxEvent event = seedEvent(NOW);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            assertEquals(List.of(event), publicationRepository.findPending(NOW, 10));
            assertTrue(publicationRepository.markPublished(event.eventId(), NOW));
            assertEquals(NOW, publishedAt(event.eventId()));
            assertTrue(publicationRepository.findPending(NOW, 10).isEmpty());
            status.setRollbackOnly();
        });

        assertNull(publishedAt(event.eventId()));
        assertEquals(List.of(event), publicationRepository.findPending(NOW, 10));
    }

    @Test
    void onlyOneConcurrentCompletionWinsAndKeepsItsTimestamp() throws Exception {
        NotificationOutboxEvent event = seedEvent(NOW);
        Instant firstTime = NOW.plusSeconds(1);
        Instant secondTime = NOW.plusSeconds(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = workers.submit(
                    () -> completeAfterStart(event.eventId(), firstTime, ready, start)
            );
            Future<Boolean> second = workers.submit(
                    () -> completeAfterStart(event.eventId(), secondTime, ready, start)
            );
            assertTrue(ready.await(5, TimeUnit.SECONDS), "두 갱신 작업이 준비되어야 합니다.");
            start.countDown();

            boolean firstUpdated = first.get(10, TimeUnit.SECONDS);
            boolean secondUpdated = second.get(10, TimeUnit.SECONDS);

            assertNotEquals(firstUpdated, secondUpdated);
            assertEquals(firstUpdated ? firstTime : secondTime, publishedAt(event.eventId()));
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    private boolean completeAfterStart(
            UUID eventId, Instant completedAt, CountDownLatch ready, CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("갱신 시작 신호를 받지 못했습니다.");
        }
        return publicationRepository.markPublished(eventId, completedAt);
    }

    private NotificationOutboxEvent seedEvent(Instant availableAt) {
        return seedEvent(UUID.randomUUID(), availableAt);
    }

    private NotificationOutboxEvent seedEvent(UUID eventId, Instant availableAt) {
        Notification notification = new NotificationFactory(
                Clock.fixed(CREATED_AT, ZoneOffset.UTC), new NotificationSchedulePolicy()
        ).create(
                "customer-1001", Channel.EMAIL, "sample@example.com",
                "예약 안내", "예약이 접수되었습니다.",
                availableAt.equals(CREATED_AT) ? null : availableAt
        );
        notificationRepository.insert(notification);
        NotificationOutboxEvent event = new NotificationOutboxEvent(
                eventId, notification.notificationId(), notification.currentDispatchId(),
                notification.generation(), notification.createdAt(), availableAt
        );
        writer.insert(event);
        return event;
    }

    private NotificationOutboxEvent seedPublishedEvent(Instant availableAt, Instant completedAt) {
        NotificationOutboxEvent event = seedEvent(availableAt);
        jdbc.sql("""
                UPDATE notification_outbox SET published_at = :completedAt WHERE event_id = :id
                """)
                .param("completedAt", completedAt.atOffset(ZoneOffset.UTC))
                .param("id", event.eventId())
                .update();
        return event;
    }

    private Instant publishedAt(UUID eventId) {
        Object value = jdbc.sql("SELECT published_at FROM notification_outbox WHERE event_id = :id")
                .param("id", eventId).query().singleRow().get("published_at");
        return value == null ? null : ((Timestamp) value).toInstant();
    }
}
