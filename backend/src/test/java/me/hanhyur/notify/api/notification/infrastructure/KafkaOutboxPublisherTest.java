package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class KafkaOutboxPublisherTest {

    private static final String TOPIC = "notification-outbox-test";
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(5);

    private final KafkaOperations<String, String> kafka = mock();
    private final SendResult<String, String> acknowledgement = mock();
    private final JsonMapper json = JsonMapper.builder().build();
    private final KafkaOutboxPublisher publisher =
            new KafkaOutboxPublisher(kafka, json, TOPIC, ACK_TIMEOUT);

    @Test
    void sendsTheConfiguredTopicNotificationKeyAndOnlyTheSixEventFields() {
        NotificationOutboxEvent event = event();
        when(kafka.send(ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.completedFuture(acknowledgement));

        assertDoesNotThrow(() -> publisher.publish(event));

        ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.captor();
        verify(kafka).send(record.capture());
        Map<?, ?> payload = json.readValue(record.getValue().value(), Map.class);
        assertAll(
                () -> assertEquals(TOPIC, record.getValue().topic()),
                () -> assertEquals(event.notificationId().toString(), record.getValue().key()),
                () -> assertEquals(Map.of(
                        "eventId", event.eventId().toString(),
                        "notificationId", event.notificationId().toString(),
                        "dispatchId", event.dispatchId().toString(),
                        "generation", event.generation(),
                        "occurredAt", event.occurredAt().toString(),
                        "availableAt", event.availableAt().toString()
                ), payload)
        );
    }

    @Test
    void waitsForAcknowledgementBeforeReturningSuccessfully() throws Exception {
        NotificationOutboxEvent event = event();
        CompletableFuture<SendResult<String, String>> pending = new CompletableFuture<>();
        CountDownLatch sent = new CountDownLatch(1);
        when(kafka.send(ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenAnswer(invocation -> {
                    sent.countDown();
                    return pending;
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> publication = executor.submit(() -> publisher.publish(event));
            assertTrue(sent.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,
                    () -> publication.get(100, TimeUnit.MILLISECONDS));

            pending.complete(acknowledgement);

            assertDoesNotThrow(() -> publication.get(2, TimeUnit.SECONDS));
        } finally {
            pending.complete(acknowledgement);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void propagatesSynchronousSendFailureWithItsCause() {
        IllegalStateException sendFailure = new IllegalStateException("동기 전송 실패");
        when(kafka.send(ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenThrow(sendFailure);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> publisher.publish(event()));

        assertSame(sendFailure, rootCause(failure));
    }

    @Test
    void propagatesAsynchronousSendFailureWithItsCause() {
        IllegalStateException sendFailure = new IllegalStateException("비동기 전송 실패");
        when(kafka.send(ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.failedFuture(sendFailure));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> publisher.publish(event()));

        assertSame(sendFailure, rootCause(failure));
    }

    @Test
    void failsWhenAcknowledgementDoesNotArriveWithinTheConfiguredTimeout() {
        CompletableFuture<SendResult<String, String>> pending = new CompletableFuture<>();
        when(kafka.send(ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(pending);
        KafkaOutboxPublisher shortWaitPublisher =
                new KafkaOutboxPublisher(kafka, json, TOPIC, Duration.ofMillis(25));

        RuntimeException failure = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertThrows(RuntimeException.class,
                        () -> shortWaitPublisher.publish(event())));

        assertInstanceOf(TimeoutException.class, rootCause(failure));
    }

    @Test
    void restoresTheInterruptFlagAndPreservesTheInterruptionCause() {
        when(kafka.send(ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();

        try {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> publisher.publish(event()));

            assertTrue(Thread.currentThread().isInterrupted());
            assertInstanceOf(InterruptedException.class, rootCause(failure));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void keepsTheSameKeyAndEventIdentityWhenRetryingAFailedPublication() {
        NotificationOutboxEvent event = event();
        when(kafka.send(ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("첫 발행 실패")))
                .thenReturn(CompletableFuture.completedFuture(acknowledgement));
        assertThrows(RuntimeException.class, () -> publisher.publish(event));

        assertDoesNotThrow(() -> publisher.publish(event));

        ArgumentCaptor<ProducerRecord<String, String>> records = ArgumentCaptor.captor();
        verify(kafka, times(2)).send(records.capture());
        List<ProducerRecord<String, String>> attempts = records.getAllValues();
        for (ProducerRecord<String, String> attempt : attempts) {
            Map<?, ?> payload = json.readValue(attempt.value(), Map.class);
            assertEquals(event.notificationId().toString(), attempt.key());
            assertEquals(event.eventId().toString(), payload.get("eventId"));
        }
        assertEquals(json.readTree(attempts.getFirst().value()),
                json.readTree(attempts.getLast().value()));
    }

    private static NotificationOutboxEvent event() {
        Instant occurredAt = Instant.parse("2026-11-18T09:00:00.123456789Z");
        return new NotificationOutboxEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 3,
                occurredAt, occurredAt.plusSeconds(60)
        );
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
