package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublisher;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class KafkaOutboxPublisher implements NotificationOutboxPublisher {

    private final KafkaOperations<String, String> kafka;
    private final JsonMapper json;
    private final String topic;
    private final Duration ackTimeout;

    public KafkaOutboxPublisher(
            KafkaOperations<String, String> kafka,
            JsonMapper json,
            String topic,
            Duration ackTimeout
    ) {
        this.kafka = Objects.requireNonNull(kafka);
        this.json = Objects.requireNonNull(json);
        this.topic = Objects.requireNonNull(topic);
        this.ackTimeout = Objects.requireNonNull(ackTimeout);

        if (topic.isBlank() || !ackTimeout.isPositive()) {
            throw new IllegalArgumentException("토픽 이름과 양수인 응답 대기 시간이 필요합니다.");
        }
    }

    @Override
    public void publish(NotificationOutboxEvent event) {
        ProducerRecord<String, String> record = toRecord(Objects.requireNonNull(event));
        awaitAcknowledgement(kafka.send(record));
    }

    private ProducerRecord<String, String> toRecord(NotificationOutboxEvent event) {
        String value = json.writeValueAsString(event);

        return new ProducerRecord<>(topic, event.notificationId().toString(), value);
    }

    private void awaitAcknowledgement(CompletableFuture<SendResult<String, String>> result) {
        try {
            result.get(TimeUnit.NANOSECONDS.convert(ackTimeout), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
