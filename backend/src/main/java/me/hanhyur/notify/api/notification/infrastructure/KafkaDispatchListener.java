package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.application.NotificationDeliveryService;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

public class KafkaDispatchListener {

    private final ObjectReader events;
    private final NotificationDeliveryService delivery;
    private final Clock clock;
    private final Executor workers;

    public KafkaDispatchListener(JsonMapper json, NotificationDeliveryService delivery, Clock clock, Executor workers) {
        this.events = json.readerFor(NotificationOutboxEvent.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.delivery = Objects.requireNonNull(delivery);
        this.clock = Objects.requireNonNull(clock);
        this.workers = Objects.requireNonNull(workers);
    }

    @KafkaListener(id = "notificationDispatchClaim", topics = "${notification.outbox.kafka.topic}",
            groupId = "${notification.dispatch.consumer.group-id}", containerFactory = "dispatchKafkaListenerContainerFactory",
            properties = {ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG + "=false",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG + "=earliest", ConsumerConfig.MAX_POLL_RECORDS_CONFIG + "=1"})
    public void receive(ConsumerRecord<String, String> record) {
        var event = decode(record.value());

        if (!Objects.equals(record.key(), event.notificationId().toString())) {
            throw new IllegalArgumentException("Kafka 키와 알림 식별자가 일치해야 합니다.");
        }

        var pending = CompletableFuture.runAsync(() -> deliverWhenDue(event), workers);
        awaitDelivery(pending);
    }

    private static void awaitDelivery(CompletableFuture<Void> pending) {
        try {
            pending.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (failure.getCause() instanceof Error cause) {
                throw cause;
            }
            throw failure;
        }
    }

    private void deliverWhenDue(NotificationOutboxEvent event) {
        Instant now = clock.instant();
        if (event.availableAt().isAfter(now)) {
            throw new IllegalStateException("발송 가능한 시각이 아닙니다.");
        }

        delivery.deliver(event, now);
    }

    private NotificationOutboxEvent decode(String payload) {
        if (payload != null) {
            try {
                NotificationOutboxEvent event = events.readValue(payload);
                if (event != null) {
                    return event;
                }
            } catch (JacksonException ex) {
                throw new IllegalArgumentException("발송 이벤트 형식이 올바르지 않습니다.");
            }
        }
        throw new IllegalArgumentException("발송 이벤트 값은 필수입니다.");
    }
}
