package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.domain.NotificationDispatchClaimRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaDispatchListenerTest {

    private static final Instant NOW = Instant.parse("2026-11-19T09:30:00Z");
    private final NotificationDispatchClaimRepository claims = mock();
    private final JsonMapper json = JsonMapper.builder().build();
    private final KafkaDispatchListener listener = new KafkaDispatchListener(json, claims, Clock.fixed(NOW, ZoneOffset.UTC));

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void submitsOneClaimForAnEventDueNow(boolean won) {
        NotificationOutboxEvent event = eventDueAt(NOW);
        when(claims.tryClaim(event, NOW)).thenReturn(won);

        listener.receive(record(event.notificationId().toString(), json.writeValueAsString(event)));

        verify(claims).tryClaim(event, NOW);
        verifyNoMoreInteractions(claims);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"another-key"})
    void rejectsAnEventWithTheWrongKey(String key) {
        assertThrows(IllegalArgumentException.class,
                () -> listener.receive(record(key, json.writeValueAsString(eventDueAt(NOW)))));
        verifyNoInteractions(claims);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "{}", "private-payload-marker"})
    void rejectsUnreadableEventsWithoutExposingThePayload(String value) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> listener.receive(record(UUID.randomUUID().toString(), value)));
        assertFalse(failure.getMessage().contains("private-payload-marker"));
        assertNull(failure.getCause());
        verifyNoInteractions(claims);
    }

    @Test
    void preservesTheRecordForRedeliveryWhenTheWorkerClockIsEarly() {
        NotificationOutboxEvent event = eventDueAt(NOW.plusSeconds(1));
        var record = record(event.notificationId().toString(), json.writeValueAsString(event));

        assertThrows(IllegalStateException.class, () -> listener.receive(record));
        verifyNoInteractions(claims);
    }

    @Test
    void propagatesAPersistenceFailureToTheKafkaContainer() {
        NotificationOutboxEvent event = eventDueAt(NOW.minusSeconds(1));
        var record = record(event.notificationId().toString(), json.writeValueAsString(event));
        var failure = new IllegalStateException("선점 저장 실패");
        when(claims.tryClaim(event, NOW)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> listener.receive(record)));
        verify(claims).tryClaim(event, NOW);
        verifyNoMoreInteractions(claims);
    }

    private NotificationOutboxEvent eventDueAt(Instant availableAt) {
        return new NotificationOutboxEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, NOW.minusSeconds(60), availableAt);
    }

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("notification.dispatch.v1", 0, 17L, key, value);
    }
}
