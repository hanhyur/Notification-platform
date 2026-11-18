package me.hanhyur.notify.api.notification.config;

import me.hanhyur.notify.api.notification.application.NotificationOutboxPublicationService;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublicationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublisher;
import me.hanhyur.notify.api.notification.infrastructure.KafkaOutboxPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class OutboxPublicationConfiguration {

    @Bean
    KafkaOutboxPublisher kafkaOutboxPublisher(
            KafkaOperations<String, String> kafka,
            JsonMapper json,
            @Value("${notification.outbox.kafka.topic}") String topic,
            @Value("${notification.outbox.kafka.ack-timeout}") Duration ackTimeout
    ) {
        return new KafkaOutboxPublisher(kafka, json, topic, ackTimeout);
    }

    @Bean
    NotificationOutboxPublicationService notificationOutboxPublicationService(
            NotificationOutboxPublicationRepository repository,
            NotificationOutboxPublisher publisher,
            Clock clock
    ) {
        return new NotificationOutboxPublicationService(repository, publisher, clock);
    }
}
