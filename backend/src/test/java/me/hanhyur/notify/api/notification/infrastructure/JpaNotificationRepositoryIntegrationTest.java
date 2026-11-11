package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationFactory;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationSchedulePolicy;
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

import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class JpaNotificationRepositoryIntegrationTest {

    private static final Instant NOW =
            Instant.parse("2026-11-11T09:00:00.123456Z");

    @Autowired
    private NotificationRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearNotifications() {
        jdbc.sql("DELETE FROM notifications").update();
    }

    @Test
    void insertsAllColumns() {
        Notification expected = sample();

        repository.insert(expected);

        Map<String, Object> row = jdbc.sql("""
                SELECT * FROM notifications WHERE notification_id = :id
                """).param("id", expected.notificationId()).query().singleRow();

        assertAll(
                () -> assertEquals(expected.notificationId(), row.get("notification_id")),
                () -> assertEquals(expected.currentDispatchId(), row.get("current_dispatch_id")),
                () -> assertEquals(expected.customerId(), row.get("customer_id")),
                () -> assertEquals(expected.channel().name(), row.get("channel")),
                () -> assertEquals(expected.recipient(), row.get("recipient")),
                () -> assertEquals(expected.title(), row.get("title")),
                () -> assertEquals(expected.content(), row.get("content")),
                () -> assertEquals(expected.status().name(), row.get("status")),
                () -> assertEquals(expected.scheduledAt(), timestamp(row.get("scheduled_at"))),
                () -> assertEquals(expected.createdAt(), timestamp(row.get("created_at"))),
                () -> assertEquals(expected.updatedAt(), timestamp(row.get("updated_at"))),
                () -> assertEquals(expected.generation(), row.get("generation")),
                () -> assertEquals(expected.retryCount(), row.get("retry_count")),
                () -> assertEquals(expected.version(), row.get("version")),
                () -> assertEquals(1L, countRows())
        );
    }

    @Test
    void restoresAllColumnsFromExistingRow() {
        Notification expected = sample();
        seed(expected);

        Notification actual = repository.findById(expected.notificationId())
                .orElseThrow();

        assertEquals(expected, actual);
    }

    @Test
    void returnsEmptyWhenIdIsMissing() {
        seed(sample());

        assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findsMatchingRowAmongMultipleNotifications() {
        Notification first = sample();
        Notification second = sample(Channel.SMS, NotificationStatus.ACCEPTED, null);
        seed(first);
        seed(second);

        assertEquals(second, repository.findById(second.notificationId()).orElseThrow());
    }

    @Test
    void rejectsDuplicateIdWithoutOverwritingRow() {
        Notification original = sample();
        seed(original);
        Notification duplicate = new Notification(
                original.notificationId(), UUID.randomUUID(),
                "customer-other", Channel.SMS, "+821012345678",
                "변경 제목", "변경 본문", NotificationStatus.QUEUED,
                null, NOW.plusSeconds(1), NOW.plusSeconds(1),
                1, 0, 0L
        );

        DataIntegrityViolationException failure = assertThrows(
                DataIntegrityViolationException.class,
                () -> repository.insert(duplicate)
        );
        assertEquals("23505", sqlCause(failure).getSQLState());

        Map<String, Object> row = jdbc.sql("""
                SELECT current_dispatch_id, title, content, version
                FROM notifications WHERE notification_id = :id
                """).param("id", original.notificationId()).query().singleRow();

        assertAll(
                () -> assertEquals(1L, countRows()),
                () -> assertEquals(original.currentDispatchId(), row.get("current_dispatch_id")),
                () -> assertEquals(original.title(), row.get("title")),
                () -> assertEquals(original.content(), row.get("content")),
                () -> assertEquals(original.version(), row.get("version"))
        );
    }

    @Test
    void flushesDuplicateFailureInsideCallerTransaction() {
        Notification original = sample();
        seed(original);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            try {
                DataIntegrityViolationException failure = assertThrows(
                        DataIntegrityViolationException.class,
                        () -> repository.insert(original)
                );
                assertEquals("23505", sqlCause(failure).getSQLState());
            } finally {
                status.setRollbackOnly();
            }
        });

        assertEquals(1L, countRows());
    }

    @Test
    void storesNullScheduleForImmediateNotification() {
        Notification expected = sample(Channel.EMAIL, NotificationStatus.QUEUED, null);

        repository.insert(expected);

        assertTrue(jdbc.sql("""
                SELECT scheduled_at IS NULL
                FROM notifications WHERE notification_id = :id
                """).param("id", expected.notificationId()).query(Boolean.class).single());
    }

    @Test
    void restoresNullScheduleForImmediateNotification() {
        Notification expected = sample(Channel.EMAIL, NotificationStatus.QUEUED, null);
        seed(expected);

        assertNull(repository.findById(expected.notificationId()).orElseThrow().scheduledAt());
    }

    @ParameterizedTest
    @EnumSource(Channel.class)
    void roundTripsEveryChannel(Channel channel) {
        Notification expected = sample(channel, NotificationStatus.QUEUED, null);

        repository.insert(expected);

        assertEquals(expected, repository.findById(expected.notificationId()).orElseThrow());
    }

    @ParameterizedTest
    @EnumSource(NotificationStatus.class)
    void roundTripsEveryStatus(NotificationStatus status) {
        Notification expected = sample(Channel.EMAIL, status, NOW.plusSeconds(60));

        repository.insert(expected);

        assertEquals(expected, repository.findById(expected.notificationId()).orElseThrow());
    }

    @Test
    void preservesLiteralPayload() {
        Notification base = sample();
        Notification expected = new Notification(
                base.notificationId(), base.currentDispatchId(),
                base.customerId(), base.channel(), "o'reilly@example.com",
                "결제 '확인'", "안내\n'); DROP TABLE notifications; -- 😀",
                base.status(), base.scheduledAt(), base.createdAt(), base.updatedAt(),
                base.generation(), base.retryCount(), base.version()
        );

        repository.insert(expected);

        assertEquals(expected, repository.findById(expected.notificationId()).orElseThrow());
        assertEquals(1L, countRows());
    }

    @Test
    void preservesVersionBeyondIntegerRange() {
        Notification base = sample();
        Notification expected = new Notification(
                base.notificationId(), base.currentDispatchId(),
                base.customerId(), base.channel(), base.recipient(),
                base.title(), base.content(), base.status(), base.scheduledAt(),
                base.createdAt(), base.updatedAt(), 3, 3, 3_000_000_000L
        );

        repository.insert(expected);

        assertEquals(expected, repository.findById(expected.notificationId()).orElseThrow());
    }

    @Test
    void rollsBackWithCallerTransaction() {
        Notification expected = sample();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () ->
                transaction.executeWithoutResult(status -> {
                    repository.insert(expected);
                    assertTrue(repository.findById(expected.notificationId()).isPresent());
                    throw new IllegalStateException("rollback-check");
                })
        );

        assertEquals(0L, jdbc.sql("""
                SELECT count(*) FROM notifications WHERE notification_id = :id
                """).param("id", expected.notificationId()).query(Long.class).single());
    }

    @Test
    void rejectsNullNotification() {
        assertThrows(NullPointerException.class, () -> repository.insert(null));
    }

    @Test
    void rejectsNullNotificationId() {
        assertThrows(NullPointerException.class, () -> repository.findById(null));
    }

    @Test
    void usesPostgresMicrosecondTimestampPrecision() {
        Instant now = Instant.parse("2026-11-11T09:00:00.123456789Z");
        NotificationFactory factory = new NotificationFactory(
                Clock.fixed(now, ZoneOffset.UTC), new NotificationSchedulePolicy()
        );
        Notification expected = factory.create(
                "customer-1001", Channel.EMAIL, "sample@example.com",
                "예약 안내", "예약이 접수되었습니다.", now.plusSeconds(60)
        );

        repository.insert(expected);
        Notification actual = repository.findById(expected.notificationId()).orElseThrow();

        Instant storedNow = Instant.parse("2026-11-11T09:00:00.123457Z");
        assertAll(
                () -> assertEquals(storedNow, actual.createdAt()),
                () -> assertEquals(storedNow, actual.updatedAt()),
                () -> assertEquals(storedNow.plusSeconds(60), actual.scheduledAt())
        );
    }

    @Test
    void persistsFactoryCreatedNotification() {
        NotificationFactory factory = new NotificationFactory(
                Clock.fixed(NOW, ZoneOffset.UTC), new NotificationSchedulePolicy()
        );
        Notification expected = factory.create(
                "customer-1001", Channel.EMAIL, "sample@example.com",
                "예약 안내", "예약이 접수되었습니다.", null
        );

        repository.insert(expected);

        assertEquals(expected, repository.findById(expected.notificationId()).orElseThrow());
    }

    private Notification sample() {
        return sample(Channel.EMAIL, NotificationStatus.RETRY_WAIT, NOW.plusSeconds(60));
    }

    private Notification sample(
            Channel channel, NotificationStatus status, Instant scheduledAt
    ) {
        String recipient = switch (channel) {
            case EMAIL -> "sample@example.com";
            case SMS -> "+821012345678";
            case KAKAOTALK -> "talk-customer-1001";
        };
        return new Notification(
                UUID.randomUUID(), UUID.randomUUID(),
                "customer-1001", channel, recipient,
                "예약 안내", "예약이 접수되었습니다.", status, scheduledAt,
                NOW.minusSeconds(120), NOW, 2, 1, 9L
        );
    }

    private void seed(Notification notification) {
        jdbc.sql("""
                INSERT INTO notifications (
                    notification_id, current_dispatch_id, customer_id, channel,
                    recipient, title, content, status, scheduled_at, created_at,
                    updated_at, generation, retry_count, version
                ) VALUES (
                    :id, :dispatchId, :customerId, :channel,
                    :recipient, :title, :content, :status, :scheduledAt, :createdAt,
                    :updatedAt, :generation, :retryCount, :version
                )
                """)
                .param("id", notification.notificationId())
                .param("dispatchId", notification.currentDispatchId())
                .param("customerId", notification.customerId())
                .param("channel", notification.channel().name())
                .param("recipient", notification.recipient())
                .param("title", notification.title())
                .param("content", notification.content())
                .param("status", notification.status().name())
                .param("scheduledAt", notification.scheduledAt() == null ? null
                        : notification.scheduledAt().atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdAt", notification.createdAt().atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("updatedAt", notification.updatedAt().atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("generation", notification.generation())
                .param("retryCount", notification.retryCount())
                .param("version", notification.version())
                .update();
    }

    private long countRows() {
        return jdbc.sql("SELECT count(*) FROM notifications").query(Long.class).single();
    }

    private static SQLException sqlCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql;
            }
        }
        throw new AssertionError("SQL 예외 원인을 찾을 수 없습니다.", failure);
    }

    private static Instant timestamp(Object value) {
        return assertInstanceOf(Timestamp.class, value).toInstant();
    }
}
