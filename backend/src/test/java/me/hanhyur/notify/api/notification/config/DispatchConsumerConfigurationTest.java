package me.hanhyur.notify.api.notification.config;

import me.hanhyur.notify.api.notification.domain.NotificationDispatchClaimRepository;
import me.hanhyur.notify.api.notification.domain.NotificationDeliveryResultRepository;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import me.hanhyur.notify.api.notification.infrastructure.KafkaDispatchListener;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchConsumerConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(DispatchConsumerConfiguration.class)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withBean(NotificationDispatchClaimRepository.class, () -> mock(NotificationDispatchClaimRepository.class))
            .withBean(NotificationDeliveryResultRepository.class, () -> mock(NotificationDeliveryResultRepository.class))
            .withBean(NotificationRepository.class, () -> mock(NotificationRepository.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withPropertyValues("spring.kafka.listener.auto-startup=false",
                    "notification.dispatch.provider.connect-timeout=2s", "notification.dispatch.provider.response-timeout=5s");

    @Test
    void doesNotRegisterConsumerByDefault() {
        assertDisabled(contextRunner);
    }

    @Test
    void doesNotRegisterConsumerWhenExplicitlyDisabled() {
        assertDisabled(contextRunner.withPropertyValues("notification.dispatch.consumer.enabled=false"));
    }

    private void assertDisabled(ApplicationContextRunner runner) {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(KafkaDispatchListener.class).isEmpty());
            assertTrue(context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainerIds().isEmpty());
        });
    }

    @Test
    void requiresAProviderEndpointBeforeStartingAConsumer() {
        contextRunner.withPropertyValues("notification.dispatch.consumer.enabled=true",
                "notification.dispatch.consumer.group-id=dispatch-test-worker", "notification.outbox.kafka.topic=dispatch-test-topic")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    while (failure.getCause() != null) {
                        failure = failure.getCause();
                    }
                    assertTrue(failure.getMessage().contains("notification.dispatch.provider.endpoint"));
                });
    }

    @Test
    void registersRecordListenerAndRetainsFailedRecordsForRedelivery() {
        contextRunner.withPropertyValues(
                "notification.dispatch.consumer.enabled=true",
                "notification.dispatch.consumer.group-id=dispatch-test-worker",
                "notification.dispatch.provider.endpoint=http://127.0.0.1/deliveries",
                "notification.outbox.kafka.topic=dispatch-test-topic",
                "spring.kafka.consumer.enable-auto-commit=true"
        ).run(context -> {
            assertNull(context.getStartupFailure());
            var registry = context.getBean(KafkaListenerEndpointRegistry.class);
            var listener = assertInstanceOf(ConcurrentMessageListenerContainer.class,
                    registry.getListenerContainer("notificationDispatchClaim"));
            assertFalse(listener.isRunning());
            var properties = listener.getContainerProperties();
            assertEquals("dispatch-test-worker", properties.getGroupId());
            assertArrayEquals(new String[]{"dispatch-test-topic"}, properties.getTopics());
            assertEquals(ContainerProperties.AckMode.RECORD, properties.getAckMode());
            assertEquals("false", properties.getKafkaConsumerProperties().getProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
            assertEquals("earliest", properties.getKafkaConsumerProperties().getProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));

            var errors = listener.getCommonErrorHandler();
            assertNotNull(errors);
            assertFalse(errors.isAckAfterHandle());
            assertFalse(errors.seeksAfterHandling());
            Consumer<?, ?> consumer = mock();
            var record = new ConsumerRecord<>("dispatch-test-topic", 0, 7L, "key", "invalid");
            for (int attempt = 0; attempt < 12; attempt++) {
                assertFalse(errors.handleOne(new IllegalArgumentException("invalid event"), record, consumer, listener));
            }
            verifyNoInteractions(consumer);
            errors.clearThreadState();
        });
    }
}
