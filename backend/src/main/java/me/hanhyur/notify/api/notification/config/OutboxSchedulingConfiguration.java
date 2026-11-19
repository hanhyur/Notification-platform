package me.hanhyur.notify.api.notification.config;

import me.hanhyur.notify.api.notification.application.NotificationOutboxPublicationService;
import me.hanhyur.notify.api.notification.infrastructure.OutboxPublicationScheduler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "notification.outbox.polling", name = "enabled", havingValue = "true")
public class OutboxSchedulingConfiguration {

    @Bean
    OutboxPublicationScheduler outboxPublicationScheduler(
            NotificationOutboxPublicationService service,
            @Value("${notification.outbox.polling.batch-size}") int batchSize
    ) {
        return new OutboxPublicationScheduler(service, batchSize);
    }
}
