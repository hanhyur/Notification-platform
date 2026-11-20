package me.hanhyur.notify.api.notification.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublicationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class NotificationOutboxPublicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T09:00:00.123456Z");

    private final List<String> calls = new ArrayList<>();
    private final RecordingRepository repository = new RecordingRepository(calls);
    private final RecordingPublisher publisher = new RecordingPublisher(calls);
    private final AdjustableClock clock = new AdjustableClock(NOW);
    private final NotificationOutboxPublicationService service =
            new NotificationOutboxPublicationService(repository, publisher, clock);
    private final Logger logger = (Logger) LoggerFactory.getLogger(NotificationOutboxPublicationService.class);
    private final ListAppender<ILoggingEvent> entries = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        entries.start();
        logger.addAppender(entries);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(entries);
        entries.stop();
    }

    @Test
    void returnsZeroForAnEmptyBatchAndUsesTheRequestedTimeAndLimit() {
        assertEquals(0, service.publishPending(3));

        assertEquals(1, repository.queryCount);
        assertEquals(NOW, repository.queriedAt);
        assertEquals(3, repository.requestedLimit);
        assertTrue(publisher.attempts.isEmpty());
        assertTrue(repository.completionAttempts.isEmpty());
    }

    @Test
    void recordsCompletionOnlyAfterPublicationUsingTheAcknowledgementTime() {
        NotificationOutboxEvent event = event();
        repository.pending.add(event);
        Instant acknowledgedAt = NOW.plusSeconds(2);
        publisher.behavior = ignored -> clock.setInstant(acknowledgedAt);

        assertEquals(1, service.publishPending(1));

        assertSame(event, publisher.attempts.getFirst());
        assertEquals(List.of("publish:" + event.eventId(), "mark:" + event.eventId()), calls);
        assertEquals(List.of(new Completion(event.eventId(), acknowledgedAt)),
                repository.completionAttempts);
        assertEquals(NOW, repository.queriedAt);
    }

    @Test
    void publishesTheLimitedBatchInRepositoryOrder() {
        NotificationOutboxEvent first = event();
        NotificationOutboxEvent second = event();
        repository.pending.addAll(List.of(first, second, event()));

        assertEquals(2, service.publishPending(2));

        assertEquals(1, repository.queryCount);
        assertEquals(List.of(first, second), publisher.attempts);
        assertEquals(List.of(
                "publish:" + first.eventId(), "mark:" + first.eventId(),
                "publish:" + second.eventId(), "mark:" + second.eventId()
        ), calls);
    }

    @Test
    void countsOnlyEventsWhoseCompletionWasNewlyRecorded() {
        NotificationOutboxEvent alreadyCompletedByAnotherWorker = event();
        NotificationOutboxEvent newlyCompleted = event();
        repository.pending.addAll(List.of(alreadyCompletedByAnotherWorker, newlyCompleted));
        repository.notUpdated.add(alreadyCompletedByAnotherWorker.eventId());

        assertEquals(1, service.publishPending(10));

        assertEquals(List.of(alreadyCompletedByAnotherWorker, newlyCompleted), publisher.attempts);
        assertEquals(2, repository.completionAttempts.size());
        assertOnlyPublicationLog(newlyCompleted);
    }

    @Test
    void propagatesPublicationFailureWithoutCompletingOrPublishingLaterEvents() {
        NotificationOutboxEvent first = event();
        repository.pending.addAll(List.of(first, event()));
        IllegalStateException failure = new IllegalStateException("발행 성공 확인 실패");
        publisher.behavior = ignored -> { throw failure; };

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.publishPending(10)));

        assertEquals(List.of(first), publisher.attempts);
        assertTrue(repository.completionAttempts.isEmpty());
    }

    @Test
    void keepsEarlierCompletionsWhenALaterPublicationFails() {
        NotificationOutboxEvent first = event();
        NotificationOutboxEvent second = event();
        repository.pending.addAll(List.of(first, second, event()));
        IllegalStateException failure = new IllegalStateException("두 번째 발행 실패");
        publisher.behavior = event -> {
            if (event.eventId().equals(second.eventId())) {
                throw failure;
            }
        };

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.publishPending(10)));

        assertEquals(List.of(first, second), publisher.attempts);
        assertEquals(Set.of(first.eventId()), repository.completed);
        assertOnlyPublicationLog(first);
    }

    @Test
    void propagatesQueryFailureWithoutSendingAnything() {
        IllegalStateException failure = new IllegalStateException("대상 조회 실패");
        repository.queryFailure = failure;

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.publishPending(1)));

        assertTrue(publisher.attempts.isEmpty());
        assertTrue(repository.completionAttempts.isEmpty());
    }

    @Test
    void propagatesCompletionFailureAfterPublicationAndStopsTheBatch() {
        NotificationOutboxEvent first = event();
        repository.pending.addAll(List.of(first, event()));
        IllegalStateException failure = new IllegalStateException("완료 기록 실패");
        repository.completionFailure = failure;

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.publishPending(10)));

        assertEquals(List.of(first), publisher.attempts);
        assertEquals(List.of(new Completion(first.eventId(), NOW)),
                repository.completionAttempts);
        assertTrue(repository.completed.isEmpty());
    }

    @Test
    void retriesTheSameEventAfterPublicationFailure() {
        NotificationOutboxEvent event = event();
        repository.pending.add(event);
        publisher.behavior = ignored -> { throw new IllegalStateException("발행 실패"); };
        assertThrows(IllegalStateException.class, () -> service.publishPending(1));
        assertTrue(publicationLogs().isEmpty());
        publisher.behavior = ignored -> {};

        assertEquals(1, service.publishPending(1));

        assertEquals(List.of(event, event), publisher.attempts);
        assertEquals(Set.of(event.eventId()), repository.completed);
        assertOnlyPublicationLog(event);
    }

    @Test
    void republishesTheSameEventWhenAcknowledgedPublicationCouldNotBeRecorded() {
        NotificationOutboxEvent event = event();
        repository.pending.add(event);
        repository.completionFailure = new IllegalStateException("완료 기록 실패");
        assertThrows(IllegalStateException.class, () -> service.publishPending(1));
        assertTrue(publicationLogs().isEmpty());
        repository.completionFailure = null;

        assertEquals(1, service.publishPending(1));

        assertEquals(List.of(event, event), publisher.attempts);
        assertEquals(Set.of(event.eventId()), repository.completed);
        assertOnlyPublicationLog(event);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveLimitsBeforeQuerying(int limit) {
        assertThrows(IllegalArgumentException.class, () -> service.publishPending(limit));

        assertEquals(0, repository.queryCount);
        assertTrue(publisher.attempts.isEmpty());
    }

    private List<ILoggingEvent> publicationLogs() {
        return entries.list.stream()
                .filter(entry -> entry.getFormattedMessage().startsWith("outbox_publication_recorded"))
                .toList();
    }

    private void assertOnlyPublicationLog(NotificationOutboxEvent event) {
        var logs = publicationLogs();
        assertEquals(1, logs.size());
        var entry = logs.getFirst();
        assertEquals(Level.INFO, entry.getLevel());
        assertThat(entry.getFormattedMessage().split(" ")).containsExactlyInAnyOrder(
                "outbox_publication_recorded", "notificationId=" + event.notificationId(),
                "dispatchId=" + event.dispatchId(), "eventId=" + event.eventId());
        assertNull(entry.getThrowableProxy());
    }

    private static NotificationOutboxEvent event() {
        return new NotificationOutboxEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                NOW.minusSeconds(120), NOW.minusSeconds(1)
        );
    }

    private record Completion(UUID eventId, Instant completedAt) {}

    private static final class RecordingRepository
            implements NotificationOutboxPublicationRepository {

        private final List<String> calls;
        private final List<NotificationOutboxEvent> pending = new ArrayList<>();
        private final Set<UUID> completed = new HashSet<>();
        private final Set<UUID> notUpdated = new HashSet<>();
        private final List<Completion> completionAttempts = new ArrayList<>();
        private Instant queriedAt;
        private int requestedLimit;
        private int queryCount;
        private RuntimeException queryFailure;
        private RuntimeException completionFailure;

        private RecordingRepository(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public List<NotificationOutboxEvent> findPending(Instant now, int limit) {
            queryCount++;
            queriedAt = now;
            requestedLimit = limit;
            if (queryFailure != null) {
                throw queryFailure;
            }
            return pending.stream()
                    .filter(event -> !completed.contains(event.eventId()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean markPublished(UUID eventId, Instant publishedAt) {
            calls.add("mark:" + eventId);
            completionAttempts.add(new Completion(eventId, publishedAt));
            if (completionFailure != null) {
                throw completionFailure;
            }
            return !notUpdated.contains(eventId) && completed.add(eventId);
        }
    }

    private static final class RecordingPublisher implements NotificationOutboxPublisher {

        private final List<String> calls;
        private final List<NotificationOutboxEvent> attempts = new ArrayList<>();
        private Consumer<NotificationOutboxEvent> behavior = ignored -> {};

        private RecordingPublisher(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void publish(NotificationOutboxEvent event) {
            attempts.add(event);
            calls.add("publish:" + event.eventId());
            behavior.accept(event);
        }
    }

    private static final class AdjustableClock extends Clock {

        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        private AdjustableClock(Instant initial) {
            this(new AtomicReference<>(initial), ZoneOffset.UTC);
        }

        private AdjustableClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        void setInstant(Instant value) {
            current.set(value);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new AdjustableClock(current, zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
