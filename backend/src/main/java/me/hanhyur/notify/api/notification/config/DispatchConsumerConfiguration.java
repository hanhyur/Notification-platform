package me.hanhyur.notify.api.notification.config;

import me.hanhyur.notify.api.notification.domain.NotificationDispatchClaimRepository;
import me.hanhyur.notify.api.notification.infrastructure.KafkaDispatchListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "notification.dispatch.consumer", name = "enabled", havingValue = "true")
public class DispatchConsumerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DispatchConsumerConfiguration.class);

    @Bean
    KafkaDispatchListener kafkaDispatchListener(JsonMapper json, NotificationDispatchClaimRepository claims, Clock clock) {
        return new KafkaDispatchListener(json, claims, clock);
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
