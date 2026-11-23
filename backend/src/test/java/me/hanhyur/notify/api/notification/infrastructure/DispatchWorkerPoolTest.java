package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.application.NotificationDeliveryService;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchWorkerPoolTest {

    private static final Instant NOW = Instant.parse("2026-11-19T09:30:00Z");
    private static final int TIMEOUT_SECONDS = 5;

    @Test
    void fullPoolRejectsTheRecordBeforeClaimAndTheSameRecordCanReturnLater() throws Exception {
        var json = JsonMapper.builder().build();
        NotificationDeliveryService delivery = mock();
        var first = event();
        var second = event();
        var overflow = event();
        var working = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var accepted = new CountDownLatch(2);
        doAnswer(call -> {
            working.countDown();
            await(release);
            return null;
        }).when(delivery).deliver(first, NOW);

        try (var pool = new DispatchWorkerPool(1, 1); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var listener = new KafkaDispatchListener(json, delivery, Clock.fixed(NOW, ZoneOffset.UTC), task -> {
                pool.execute(task);
                accepted.countDown();
            });
            try {
                var running = callers.submit(() -> listener.receive(record(first, json)));
                await(working);
                var queued = callers.submit(() -> listener.receive(record(second, json)));
                await(accepted);
                var rejected = record(overflow, json);
                assertThrows(RejectedExecutionException.class, () -> listener.receive(rejected));
                verify(delivery, never()).deliver(overflow, NOW);
                verify(delivery, never()).deliver(second, NOW);
                assertFalse(running.isDone());
                assertFalse(queued.isDone());

                release.countDown();
                running.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                queued.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                listener.receive(rejected);
                verify(delivery).deliver(first, NOW);
                verify(delivery).deliver(second, NOW);
                verify(delivery).deliver(overflow, NOW);
                verifyNoMoreInteractions(delivery);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void closeWaitsForAcceptedWorkAndRefusesFurtherSubmissions() throws Exception {
        var working = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closing = new CountDownLatch(1);
        List<String> completed = new CopyOnWriteArrayList<>();
        var pool = new DispatchWorkerPool(1, 1);
        try (pool; var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                pool.execute(() -> {
                    working.countDown();
                    await(release);
                    completed.add("running");
                });
                await(working);
                pool.execute(() -> completed.add("queued"));
                var closed = caller.submit(() -> {
                    closing.countDown();
                    pool.close();
                    return List.copyOf(completed);
                });
                await(closing);
                assertThrows(TimeoutException.class, () -> closed.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                assertThat(closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).containsExactly("running", "queued");
                assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> completed.add("late")));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void zeroQueueCapacityRejectsWorkWhileAllWorkersAreBusy() {
        var working = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = new DispatchWorkerPool(1, 0)) {
            pool.execute(() -> {
                working.countDown();
                await(release);
            });
            try {
                await(working);
                assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> fail("대기열이 없습니다.")));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void reusedWorkerOnlyReceivesTheCallingRequestIdAndDropsThePreviousContext() throws Exception {
        String requestId = UUID.randomUUID().toString();
        try (var pool = new DispatchWorkerPool(1, 1)) {
            try (var request = MDC.putCloseable("requestId", requestId);
                 var privateContext = MDC.putCloseable("privateField", "local")) {
                var first = CompletableFuture.supplyAsync(() -> {
                    Map<String, String> captured = MDC.getCopyOfContextMap();
                    MDC.put("workerField", "previous");
                    return captured;
                }, pool::execute);
                assertThat(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).containsExactlyEntriesOf(Map.of("requestId", requestId));
                assertEquals("local", MDC.get("privateField"));
                assertEquals(requestId, MDC.get("requestId"));
            }
            var second = CompletableFuture.supplyAsync(MDC::getCopyOfContextMap, pool::execute);
            assertThat(second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNullOrEmpty();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "발송 작업의 다음 단계에 도달해야 합니다.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static NotificationOutboxEvent event() {
        return new NotificationOutboxEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, NOW, NOW);
    }

    private static ConsumerRecord<String, String> record(NotificationOutboxEvent event, JsonMapper json) {
        return new ConsumerRecord<>("notification.dispatch.v1", 0, 0L, event.notificationId().toString(), json.writeValueAsString(event));
    }
}
