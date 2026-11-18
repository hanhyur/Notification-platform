package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.application.NotificationOutboxPublicationService;
import me.hanhyur.notify.api.notification.application.NotificationRegistrationService;
import me.hanhyur.notify.api.notification.application.RegisterNotificationCommand;
import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import me.hanhyur.notify.api.support.PostgresTestConfiguration;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import({
        PostgresTestConfiguration.class,
        KafkaOutboxPublisherIntegrationTest.KafkaTestConfiguration.class
})
class KafkaOutboxPublisherIntegrationTest {

    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private NotificationRegistrationService registrationService;

    @Autowired
    private NotificationOutboxPublicationService publicationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProducerFactory<?, ?> producerFactory;

    @Value("${notification.outbox.kafka.topic}")
    private String topic;

    @AfterEach
    void clearRows() {
        jdbc.sql("DELETE FROM notification_outbox").update();
        jdbc.sql("DELETE FROM notifications").update();
    }

    @Test
    @Timeout(45)
    void publishesARegisteredOutboxEventToKafkaAndCommitsCompletion() throws Exception {
        assertEquals("notification.dispatch.v1", topic);
        Map<String, Object> producerProperties = producerFactory.getConfigurationProperties();
        assertEquals("all", producerProperties.get(ProducerConfig.ACKS_CONFIG));
        assertEquals(StringSerializer.class,
                producerProperties.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
        assertEquals(StringSerializer.class,
                producerProperties.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        createTopic();
        Notification notification = registrationService.register(new RegisterNotificationCommand(
                "customer-1001", Channel.EMAIL, "sample@example.com",
                "주문 안내", "주문이 접수되었습니다.", null
        ));
        Notification persistedNotification = notificationRepository.findById(
                notification.notificationId()).orElseThrow();
        OutboxSnapshot before = outbox(notification.notificationId());
        assertNull(before.publishedAt());

        Instant startedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertEquals(1, publicationService.publishPending(1));
        Instant finishedAt = Instant.now().plus(1, ChronoUnit.MICROS);

        try (KafkaConsumer<String, String> consumer = consumer()) {
            TopicPartition partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));

            ConsumerRecord<String, String> record = receiveOne(consumer);
            assertEquals(topic, record.topic());
            assertEquals(notification.notificationId().toString(), record.key());
            assertEventPayload(before.event(), record.value());
        }

        OutboxSnapshot after = outbox(notification.notificationId());
        assertEquals(before.event(), after.event());
        assertNotNull(after.publishedAt());
        assertFalse(after.publishedAt().isBefore(startedAt));
        assertFalse(after.publishedAt().isAfter(finishedAt));
        assertEquals(persistedNotification, notificationRepository.findById(
                notification.notificationId()).orElseThrow());
        assertEquals(0, publicationService.publishPending(1));
    }

    private void createTopic() throws Exception {
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()
        ))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
                    .all().get(10, TimeUnit.SECONDS);
        }
    }

    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-integration-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000,
                ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000
        ));
    }

    private ConsumerRecord<String, String> receiveOne(KafkaConsumer<String, String> consumer) {
        long deadline = System.nanoTime() + RECEIVE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
            if (!records.isEmpty()) {
                assertEquals(1, records.count());
                return records.iterator().next();
            }
        }
        return fail("제한 시간 안에 Outbox Kafka 레코드를 수신하지 못했습니다.");
    }

    private void assertEventPayload(NotificationOutboxEvent event, String value) {
        JsonNode payload = objectMapper.readTree(value);
        assertTrue(payload.isObject());
        assertEquals(6, payload.size());
        assertEquals(Set.of(
                "eventId", "notificationId", "dispatchId", "generation", "occurredAt", "availableAt"
        ), Set.copyOf(payload.propertyNames()));
        assertEquals(event.eventId().toString(), payload.path("eventId").asString());
        assertEquals(event.notificationId().toString(), payload.path("notificationId").asString());
        assertEquals(event.dispatchId().toString(), payload.path("dispatchId").asString());
        assertTrue(payload.path("generation").isIntegralNumber());
        assertEquals(event.generation(), payload.path("generation").intValue());
        assertUtcInstant(event.occurredAt(), payload.path("occurredAt"));
        assertUtcInstant(event.availableAt(), payload.path("availableAt"));
    }

    private void assertUtcInstant(Instant expected, JsonNode value) {
        assertTrue(value.isString());
        String timestamp = value.stringValue();
        assertTrue(timestamp.endsWith("Z"));
        assertEquals(expected, Instant.parse(timestamp));
    }

    private OutboxSnapshot outbox(UUID notificationId) {
        return jdbc.sql("""
                SELECT event_id, notification_id, dispatch_id, generation, occurred_at,
                       available_at, published_at
                FROM notification_outbox
                WHERE notification_id = :id
                """).param("id", notificationId).query((rs, rowNum) -> {
            OffsetDateTime publishedAt = rs.getObject("published_at", OffsetDateTime.class);
            return new OutboxSnapshot(new NotificationOutboxEvent(
                    rs.getObject("event_id", UUID.class),
                    rs.getObject("notification_id", UUID.class),
                    rs.getObject("dispatch_id", UUID.class),
                    rs.getInt("generation"),
                    rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("available_at", OffsetDateTime.class).toInstant()
            ), publishedAt == null ? null : publishedAt.toInstant());
        }).single();
    }

    private record OutboxSnapshot(NotificationOutboxEvent event, Instant publishedAt) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaTestConfiguration {

        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer("apache/kafka:4.2.2")
                    .withStartupTimeout(Duration.ofSeconds(90));
        }
    }
}
