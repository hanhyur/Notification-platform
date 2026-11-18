package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublicationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublisher;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import({
        PostgresTestConfiguration.class,
        NotificationOutboxPublicationServiceIntegrationTest.PublicationTestConfiguration.class
})
class NotificationOutboxPublicationServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-12T09:00:00.123456Z");
    private static final Instant CREATED_AT = NOW.minusSeconds(120);

    @Autowired
    private NotificationOutboxPublicationService service;

    @Autowired
    private NotificationRegistrationService registrationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JpaNotificationOutboxRepository delegate;

    @Autowired
    private FailingPublicationRepository publicationRepository;

    @Autowired
    private RecordingPublisher publisher;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearRows() {
        publicationRepository.failCompletionWith(null);
        publisher.attempts.clear();
        publisher.behavior = ignored -> {};
        jdbc.sql("DELETE FROM notification_outbox").update();
        jdbc.sql("DELETE FROM notifications").update();
    }

    @Test
    void publishesOnlyDueEventsAndCommitsCompletionWithoutChangingNotifications() {
        NotificationOutboxEvent past = seedEvent(CREATED_AT);
        NotificationOutboxEvent boundary = seedEvent(NOW);
        NotificationOutboxEvent future = seedEvent(NOW.plusSeconds(1));
        NotificationOutboxEvent previouslyPublished = seedEvent(NOW.minusSeconds(1));
        jdbc.sql("UPDATE notification_outbox SET published_at = :at WHERE event_id = :id")
                .param("at", NOW.atOffset(ZoneOffset.UTC))
                .param("id", previouslyPublished.eventId()).update();
        Notification original = notificationRepository.findById(boundary.notificationId())
                .orElseThrow();
        publisher.behavior = event -> assertNull(publishedAt(event.eventId()));

        assertEquals(2, service.publishPending(10));

        assertEquals(List.of(past, boundary), publisher.attempts);
        assertEquals(NOW, publishedAt(past.eventId()));
        assertEquals(NOW, publishedAt(boundary.eventId()));
        assertNull(publishedAt(future.eventId()));
        assertEquals(original, notificationRepository.findById(boundary.notificationId())
                .orElseThrow());
    }

    @Test
    void leavesAnEventPendingWhenPublicationFails() {
        NotificationOutboxEvent event = seedEvent(NOW);
        IllegalStateException failure = new IllegalStateException("발행 성공 확인 실패");
        publisher.behavior = ignored -> { throw failure; };

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.publishPending(1)));

        assertNull(publishedAt(event.eventId()));
        assertEquals(List.of(event), publicationRepository.findPending(NOW, 1));
    }

    @Test
    void preservesCommittedCompletionsWhenTheNextEventFails() {
        NotificationOutboxEvent first = seedEvent(CREATED_AT);
        NotificationOutboxEvent second = seedEvent(CREATED_AT.plusSeconds(1));
        NotificationOutboxEvent third = seedEvent(CREATED_AT.plusSeconds(2));
        IllegalStateException failure = new IllegalStateException("두 번째 발행 실패");
        publisher.behavior = event -> {
            if (event.eventId().equals(second.eventId())) {
                throw failure;
            }
        };

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.publishPending(10)));

        assertEquals(List.of(first, second), publisher.attempts);
        assertEquals(NOW, publishedAt(first.eventId()));
        assertNull(publishedAt(second.eventId()));
        assertNull(publishedAt(third.eventId()));
    }

    @Test
    void retriesTheSameAcknowledgedEventAfterCompletionTransactionRollsBack() {
        NotificationOutboxEvent event = seedEvent(NOW);
        IllegalStateException failure = new IllegalStateException("완료 갱신 후 실패");
        publicationRepository.failCompletionWith(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.publishPending(1)));

        assertNull(publishedAt(event.eventId()));
        assertEquals(List.of(event), publicationRepository.findPending(NOW, 1));
        publicationRepository.failCompletionWith(null);

        assertEquals(1, service.publishPending(1));
        assertEquals(List.of(event, event), publisher.attempts);
        assertEquals(NOW, publishedAt(event.eventId()));
    }

    @Test
    void doesNotCountAnEventCompletedByAnotherWorkerDuringPublication() {
        NotificationOutboxEvent event = seedEvent(NOW);
        publisher.behavior = sent -> assertTrue(delegate.markPublished(sent.eventId(), NOW));

        assertEquals(0, service.publishPending(1));

        assertEquals(List.of(event), publisher.attempts);
        assertEquals(NOW, publishedAt(event.eventId()));
        assertEquals(0, service.publishPending(1));
        assertEquals(1, publisher.attempts.size());
    }

    @Test
    void rejectsACallerTransactionBeforePublishingAnything() {
        NotificationOutboxEvent event = seedEvent(NOW);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            assertThrows(IllegalTransactionStateException.class,
                    () -> service.publishPending(1));
            status.setRollbackOnly();
        });

        assertTrue(publisher.attempts.isEmpty());
        assertNull(publishedAt(event.eventId()));
    }

    private NotificationOutboxEvent seedEvent(Instant availableAt) {
        Notification notification = registrationService.register(new RegisterNotificationCommand(
                "customer-1001", Channel.EMAIL, "sample@example.com",
                "예약 안내", "예약이 접수되었습니다.",
                availableAt.equals(CREATED_AT) ? null : availableAt
        ));
        return jdbc.sql("""
                SELECT event_id, notification_id, dispatch_id, generation, occurred_at, available_at
                FROM notification_outbox WHERE notification_id = :id
                """).param("id", notification.notificationId()).query((rs, rowNum) ->
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

    private Instant publishedAt(UUID eventId) {
        Object value = jdbc.sql("SELECT published_at FROM notification_outbox WHERE event_id = :id")
                .param("id", eventId).query().singleRow().get("published_at");
        return value == null ? null : ((Timestamp) value).toInstant();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PublicationTestConfiguration {

        @Bean
        @Primary
        Clock publicationRegistrationClock() {
            return Clock.fixed(CREATED_AT, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }

        @Bean
        @Primary
        FailingPublicationRepository failingPublicationRepository(
                JpaNotificationOutboxRepository delegate
        ) {
            return new FailingPublicationRepository(delegate);
        }

        @Bean
        @Primary
        NotificationOutboxPublicationService publicationService(
                NotificationOutboxPublicationRepository repository,
                RecordingPublisher publisher
        ) {
            return new NotificationOutboxPublicationService(
                    repository, publisher, Clock.fixed(NOW, ZoneOffset.UTC)
            );
        }
    }

    static class FailingPublicationRepository implements NotificationOutboxPublicationRepository {

        private final JpaNotificationOutboxRepository delegate;
        private RuntimeException completionFailure;

        FailingPublicationRepository(JpaNotificationOutboxRepository delegate) {
            this.delegate = delegate;
        }

        public void failCompletionWith(RuntimeException failure) {
            this.completionFailure = failure;
        }

        @Override
        public List<NotificationOutboxEvent> findPending(Instant now, int limit) {
            return delegate.findPending(now, limit);
        }

        @Override
        @Transactional
        public boolean markPublished(UUID eventId, Instant publishedAt) {
            boolean updated = delegate.markPublished(eventId, publishedAt);
            if (completionFailure != null) {
                throw completionFailure;
            }
            return updated;
        }
    }

    static class RecordingPublisher implements NotificationOutboxPublisher {

        private final List<NotificationOutboxEvent> attempts = new ArrayList<>();
        private Consumer<NotificationOutboxEvent> behavior = ignored -> {};

        @Override
        public void publish(NotificationOutboxEvent event) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "브로커 응답을 기다리는 동안 DB 트랜잭션을 유지하지 않아야 합니다.");
            attempts.add(event);
            behavior.accept(event);
        }
    }
}
