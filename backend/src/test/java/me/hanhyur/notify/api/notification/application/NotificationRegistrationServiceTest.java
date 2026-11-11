package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationFactory;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationSchedulePolicy;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class NotificationRegistrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T09:00:00.123456Z");

    private final RecordingNotificationRepository repository =
            new RecordingNotificationRepository();
    private final NotificationRegistrationService service =
            new NotificationRegistrationService(
                    new NotificationFactory(
                            Clock.fixed(NOW, ZoneOffset.UTC),
                            new NotificationSchedulePolicy()
                    ),
                    repository
            );

    @Test
    void registersImmediateNotificationAndReturnsTheSavedInstance() {
        RegisterNotificationCommand command = command(null);

        Notification actual = service.register(command);

        assertEquals(1, repository.saved.size());
        assertAll(
                () -> assertSame(repository.saved.getFirst(), actual),
                () -> assertEquals(command.customerId(), actual.customerId()),
                () -> assertEquals(command.channel(), actual.channel()),
                () -> assertEquals(command.recipient(), actual.recipient()),
                () -> assertEquals(command.title(), actual.title()),
                () -> assertEquals(command.content(), actual.content()),
                () -> assertEquals(NotificationStatus.QUEUED, actual.status()),
                () -> assertNull(actual.scheduledAt()),
                () -> assertEquals(NOW, actual.createdAt()),
                () -> assertEquals(NOW, actual.updatedAt()),
                () -> assertEquals(1, actual.generation()),
                () -> assertEquals(0, actual.retryCount()),
                () -> assertEquals(0L, actual.version())
        );
    }

    @Test
    void keepsFutureScheduleAndScheduledStatus() {
        Instant scheduledAt = NOW.plusSeconds(60);

        Notification actual = service.register(command(scheduledAt));

        assertAll(
                () -> assertEquals(1, repository.saved.size()),
                () -> assertEquals(NotificationStatus.SCHEDULED, actual.status()),
                () -> assertEquals(scheduledAt, actual.scheduledAt())
        );
    }

    @ParameterizedTest
    @MethodSource("invalidSchedules")
    void rejectsInvalidSchedulesWithoutWriting(Instant scheduledAt) {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.register(command(scheduledAt))
        );

        assertTrue(repository.saved.isEmpty());
    }

    static Stream<Instant> invalidSchedules() {
        return Stream.of(
                NOW,
                NOW.minusNanos(1),
                NOW.plus(Duration.ofDays(30)).plusNanos(1)
        );
    }

    @Test
    void propagatesRepositoryFailure() {
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("저장 실패");
        repository.failure = failure;

        DataIntegrityViolationException actual = assertThrows(
                DataIntegrityViolationException.class,
                () -> service.register(command(null))
        );

        assertSame(failure, actual);
        assertTrue(repository.saved.isEmpty());
    }

    @Test
    void rejectsNullCommandWithoutWriting() {
        assertThrows(NullPointerException.class, () -> service.register(null));

        assertTrue(repository.saved.isEmpty());
    }

    @Test
    void assignsSeparateIdsToIndependentRegistrations() {
        Notification first = service.register(command(null));
        Notification second = service.register(command(null));

        assertAll(
                () -> assertEquals(2, repository.saved.size()),
                () -> assertEquals(4, new HashSet<>(List.of(
                        first.notificationId(), first.currentDispatchId(),
                        second.notificationId(), second.currentDispatchId()
                )).size())
        );
    }

    @Test
    void commandToStringDoesNotExposeRecipientOrPayload() {
        RegisterNotificationCommand command = command(null);

        String description = command.toString();

        assertAll(
                () -> assertFalse(description.contains(command.customerId())),
                () -> assertFalse(description.contains(command.recipient())),
                () -> assertFalse(description.contains(command.title())),
                () -> assertFalse(description.contains(command.content()))
        );
    }

    private static RegisterNotificationCommand command(Instant scheduledAt) {
        return new RegisterNotificationCommand(
                "customer-1001", Channel.EMAIL, "sample@example.com",
                "예약 안내", "예약이 접수되었습니다.", scheduledAt
        );
    }

    private static final class RecordingNotificationRepository
            implements NotificationRepository {

        private final List<Notification> saved = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public void insert(Notification notification) {
            if (failure != null) {
                throw failure;
            }
            saved.add(notification);
        }

        @Override
        public Optional<Notification> findById(UUID notificationId) {
            return saved.stream()
                    .filter(notification -> notification.notificationId().equals(notificationId))
                    .findFirst();
        }
    }
}
