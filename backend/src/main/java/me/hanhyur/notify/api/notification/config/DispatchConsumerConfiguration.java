package me.hanhyur.notify.api.notification.config;

import me.hanhyur.notify.api.notification.application.NotificationDeliveryService;
import me.hanhyur.notify.api.notification.domain.NotificationDeliveryResultRepository;
import me.hanhyur.notify.api.notification.domain.NotificationDispatchClaimRepository;
import me.hanhyur.notify.api.notification.domain.NotificationProvider;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import me.hanhyur.notify.api.notification.infrastructure.KafkaDispatchListener;
import me.hanhyur.notify.api.notification.infrastructure.SandboxHttpNotificationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "notification.dispatch.consumer", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DispatchConsumerConfiguration.ProviderSettings.class)
public class DispatchConsumerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DispatchConsumerConfiguration.class);

    @ConfigurationProperties("notification.dispatch.provider")
    public record ProviderSettings(URI endpoint, Duration connectTimeout, Duration responseTimeout) {
        public ProviderSettings {
            if (endpoint == null) {
                throw new IllegalArgumentException("소비자를 켜려면 notification.dispatch.provider.endpoint가 필요합니다.");
            }
        }
    }

    @Bean
    SandboxHttpNotificationProvider sandboxNotificationProvider(JsonMapper json, ProviderSettings settings) {
        return new SandboxHttpNotificationProvider(
                json, settings.endpoint(), settings.connectTimeout(), settings.responseTimeout());
    }

    @Bean
    NotificationDeliveryService notificationDeliveryService(
            NotificationDispatchClaimRepository claims, NotificationRepository notifications,
            NotificationProvider provider, NotificationDeliveryResultRepository results, Clock clock, ProviderSettings settings
    ) {
        Duration recoveryDelay = settings.connectTimeout().plus(settings.responseTimeout()).plusSeconds(30);
        return new NotificationDeliveryService(claims, notifications, provider, results, clock, recoveryDelay);
    }

    @Bean
    KafkaDispatchListener kafkaDispatchListener(JsonMapper json, NotificationDeliveryService delivery, Clock clock) {
        return new KafkaDispatchListener(json, delivery, clock);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> dispatchKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory
    ) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(false);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        var errors = new DefaultErrorHandler(new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        errors.setClassifications(Map.of(), true);
        errors.setSeekAfterError(false);
        errors.setAckAfterHandle(false);
        errors.setRetryListeners((record, failure, attempt) -> {
            if (attempt == 1) {
                log.warn("발송 이벤트 재시도 예정. topic={}, partition={}, offset={}",
                        record.topic(), record.partition(), record.offset());
            }
        });
        factory.setCommonErrorHandler(errors);

        return factory;
    }
}
