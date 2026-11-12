package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import me.hanhyur.notify.api.notification.infrastructure.JpaNotificationRepository;
import me.hanhyur.notify.api.support.PostgresTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import({
        PostgresTestConfiguration.class,
        NotificationRegistrationServiceIntegrationTest.RegistrationTestConfiguration.class
})
class NotificationRegistrationServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T09:00:00.123456Z");

    @Autowired
    private NotificationRegistrationService service;

    @Autowired
    private FailingAfterInsertRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearNotifications() {
        repository.failAfterInsert = false;
        jdbc.sql("DELETE FROM notification_outbox").update();
        jdbc.sql("DELETE FROM notifications").update();
    }

    @Test
    void commitsImmediateRegistrationBeforeReturning() {
        RegisterNotificationCommand command = command(Channel.EMAIL, null);

        Notification actual = service.register(command);

        Map<String, Object> row = jdbc.sql("""
                SELECT customer_id, channel, recipient, title, content, status, scheduled_at
                FROM notifications WHERE notification_id = :id
                """).param("id", actual.notificationId()).query().singleRow();
        assertAll(
                () -> assertEquals(1L, countRows()),
                () -> assertEquals(command.customerId(), row.get("customer_id")),
                () -> assertEquals(command.channel().name(), row.get("channel")),
                () -> assertEquals(command.recipient(), row.get("recipient")),
                () -> assertEquals(command.title(), row.get("title")),
                () -> assertEquals(command.content(), row.get("content")),
                () -> assertEquals("QUEUED", row.get("status")),
                () -> assertNull(row.get("scheduled_at")),
                () -> assertEquals(NOW, actual.createdAt()),
                () -> assertEquals(NOW, actual.updatedAt()),
                () -> assertEquals(actual, repository.findById(actual.notificationId()).orElseThrow())
        );
    }

    @Test
    void commitsScheduleAtExactlyThirtyDays() {
        Instant scheduledAt = NOW.plus(Duration.ofDays(30));

        Notification actual = service.register(command(Channel.EMAIL, scheduledAt));

        Notification stored = repository.findById(actual.notificationId()).orElseThrow();
        assertAll(
                () -> assertEquals(1L, countRows()),
                () -> assertEquals(NotificationStatus.SCHEDULED, stored.status()),
                () -> assertEquals(scheduledAt, stored.scheduledAt()),
                () -> assertEquals(actual, stored)
        );
    }

    @ParameterizedTest
    @EnumSource(Channel.class)
    void registersEverySupportedChannel(Channel channel) {
        RegisterNotificationCommand command = command(channel, null);

        Notification actual = service.register(command);

        Notification stored = repository.findById(actual.notificationId()).orElseThrow();
        assertAll(
                () -> assertEquals(1L, countRows()),
                () -> assertEquals(channel, stored.channel()),
                () -> assertEquals(command.recipient(), stored.recipient()),
                () -> assertEquals(NotificationStatus.QUEUED, stored.status())
        );
    }

    @ParameterizedTest
    @MethodSource("invalidSchedules")
    void rejectsInvalidScheduleWithoutPersisting(Instant scheduledAt) {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.register(command(Channel.EMAIL, scheduledAt))
        );

        assertEquals(0L, countRows());
    }

    static Stream<Instant> invalidSchedules() {
        return Stream.of(
                NOW,
                NOW.minusNanos(1),
                NOW.plus(Duration.ofDays(30)).plusNanos(1)
        );
    }

    @Test
    void propagatesDatabaseFailureWithoutCommitting() {
        RegisterNotificationCommand command = new RegisterNotificationCommand(
                "customer-1001", Channel.EMAIL, "sample@example.com",
                "예약 안내", "x".repeat(2001), null
        );

        assertThrows(DataIntegrityViolationException.class, () -> service.register(command));

        assertEquals(0L, countRows());
    }

    @Test
    void rollsBackInsertWhenTheRegistrationFailsAfterWriting() {
        Notification existing = service.register(command(Channel.EMAIL, null));
        repository.failAfterInsert = true;

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> service.register(command(Channel.SMS, null))
        );

        assertEquals("저장 후 등록 처리 실패", failure.getMessage());
        assertEquals(1L, countRows());
        assertEquals(existing, repository.findById(existing.notificationId()).orElseThrow());
    }

    @Test
    void participatesInCallerTransactionAndRollsBackWithIt() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        UUID id = transaction.execute(status -> {
            Notification actual = service.register(command(Channel.EMAIL, null));
            assertEquals(1L, countRows());
            status.setRollbackOnly();
            return actual.notificationId();
        });

        assertNotNull(id);
        assertEquals(0L, countRows());
        assertTrue(repository.findById(id).isEmpty());
    }

    private long countRows() {
        return jdbc.sql("SELECT count(*) FROM notifications").query(Long.class).single();
    }

    private static RegisterNotificationCommand command(Channel channel, Instant scheduledAt) {
        String recipient = switch (channel) {
            case EMAIL -> "sample@example.com";
            case SMS -> "+821012345678";
            case KAKAOTALK -> "kakao-customer-1001";
        };
        return new RegisterNotificationCommand(
                "customer-1001", channel, recipient,
                "예약 안내", "예약이 접수되었습니다.", scheduledAt
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RegistrationTestConfiguration {

        @Bean
        @Primary
        Clock registrationTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        FailingAfterInsertRepository failingAfterInsertRepository(
                JpaNotificationRepository delegate
        ) {
            return new FailingAfterInsertRepository(delegate);
        }
    }

    static class FailingAfterInsertRepository implements NotificationRepository {

        private final JpaNotificationRepository delegate;
        private boolean failAfterInsert;

        FailingAfterInsertRepository(JpaNotificationRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public void insert(Notification notification) {
            delegate.insert(notification);
            if (failAfterInsert) {
                failAfterInsert = false;
                throw new IllegalStateException("저장 후 등록 처리 실패");
            }
        }

        @Override
        public Optional<Notification> findById(UUID notificationId) {
            return delegate.findById(notificationId);
        }
    }
}
