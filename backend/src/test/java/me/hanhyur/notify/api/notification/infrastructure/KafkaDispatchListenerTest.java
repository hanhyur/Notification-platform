package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.application.NotificationDeliveryService;
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
    private final NotificationDeliveryService delivery = mock();
    private final JsonMapper json = JsonMapper.builder().build();
    private final KafkaDispatchListener listener = new KafkaDispatchListener(json, delivery, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void dispatchesAnEventDueNow() {
        NotificationOutboxEvent event = eventDueAt(NOW);

        listener.receive(record(event.notificationId().toString(), json.writeValueAsString(event)));

        verify(delivery).deliver(event, NOW);
        verifyNoMoreInteractions(delivery);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"another-key"})
    void rejectsAnEventWithTheWrongKey(String key) {
        assertThrows(IllegalArgumentException.class,
                () -> listener.receive(record(key, json.writeValueAsString(eventDueAt(NOW)))));
        verifyNoInteractions(delivery);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "{}", "private-payload-marker"})
    void rejectsUnreadableEventsWithoutExposingThePayload(String value) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> listener.receive(record(UUID.randomUUID().toString(), value)));
        assertFalse(failure.getMessage().contains("private-payload-marker"));
        assertNull(failure.getCause());
        verifyNoInteractions(delivery);
    }

    @Test
    void preservesTheRecordForRedeliveryWhenTheWorkerClockIsEarly() {
        NotificationOutboxEvent event = eventDueAt(NOW.plusSeconds(1));
        var record = record(event.notificationId().toString(), json.writeValueAsString(event));

        assertThrows(IllegalStateException.class, () -> listener.receive(record));
        verifyNoInteractions(delivery);
    }

    @Test
    void propagatesAPersistenceFailureToTheKafkaContainer() {
        NotificationOutboxEvent event = eventDueAt(NOW.minusSeconds(1));
        var record = record(event.notificationId().toString(), json.writeValueAsString(event));
        var failure = new IllegalStateException("선점 저장 실패");
        doThrow(failure).when(delivery).deliver(event, NOW);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> listener.receive(record)));
        verify(delivery).deliver(event, NOW);
        verifyNoMoreInteractions(delivery);
    }

    private NotificationOutboxEvent eventDueAt(Instant availableAt) {
        return new NotificationOutboxEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, NOW.minusSeconds(60), availableAt);
    }

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("notification.dispatch.v1", 0, 17L, key, value);
    }
}
