package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.application.NotificationOutboxPublicationService;
import me.hanhyur.notify.api.notification.application.NotificationRegistrationService;
import me.hanhyur.notify.api.notification.application.RegisterNotificationCommand;
import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublicationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublisher;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import me.hanhyur.notify.api.support.PostgresTestConfiguration;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "notification.dispatch.consumer.enabled=true",
        "notification.dispatch.consumer.group-id=dispatch-claim-integration",
        "notification.outbox.kafka.topic=dispatch-claim-test"
})
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class, KafkaDispatchClaimIntegrationTest.BrokerConfiguration.class})
@DirtiesContext
class KafkaDispatchClaimIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private NotificationRegistrationService registration;
    @Autowired
    private NotificationOutboxPublicationService publication;
    @Autowired
    private NotificationOutboxPublicationRepository outbox;
    @Autowired
    private NotificationOutboxPublisher publisher;
    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private KafkaContainer kafka;
    @Value("${notification.outbox.kafka.topic}")
    private String topic;
    @Value("${notification.dispatch.consumer.group-id}")
    private String groupId;

    @Test
    @Timeout(45)
    void commitsBothKafkaRecordsButStartsOnlyOneAttemptForTheDispatch() throws Exception {
        Notification registered = registration.register(new RegisterNotificationCommand(
                "customer-1001", Channel.EMAIL, "sample@example.com", "주문 안내", "주문이 접수되었습니다.", null));
        UUID id = registered.notificationId();
        NotificationOutboxEvent event = outbox.findPending(Instant.now(), 1).getFirst();
        assertEquals(id, event.notificationId());
        assertEquals(1, publication.publishPending(1));

        await("첫 Kafka 이벤트의 발송 선점").atMost(WAIT).until(() ->
                notifications.findById(id).orElseThrow().status() == NotificationStatus.SENDING);
        Notification claimed = notifications.findById(id).orElseThrow();
        Attempt first = singleAttempt(id);
        assertAll(
                () -> assertEquals(1L, claimed.version()),
                () -> assertEquals(event.dispatchId(), first.dispatchId()),
                () -> assertEquals(1, first.number()),
                () -> assertEquals("STARTED", first.status()),
                () -> assertEquals(claimed.updatedAt(), first.startedAt())
        );

        publisher.publish(event);
        TopicPartition partition = new TopicPartition(topic, 0);
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            await("중복 이벤트까지 소비 오프셋 커밋").atMost(WAIT).until(() -> {
                OffsetAndMetadata committed = admin.listConsumerGroupOffsets(groupId)
                        .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(partition);
                return committed != null && committed.offset() == 2L;
            });
        }
        assertEquals(claimed, notifications.findById(id).orElseThrow());
        assertEquals(first, singleAttempt(id));
    }

    private Attempt singleAttempt(UUID notificationId) {
        return jdbc.sql("""
                SELECT attempt_id, dispatch_id, attempt_no, status, started_at
                FROM notification_delivery_attempts WHERE notification_id = :id
                """).param("id", notificationId)
                .query((rs, row) -> new Attempt(
                        rs.getObject("attempt_id", UUID.class), rs.getObject("dispatch_id", UUID.class),
                        rs.getInt("attempt_no"), rs.getString("status"),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant())).single();
    }

    private record Attempt(UUID id, UUID dispatchId, int number, String status, Instant startedAt) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class BrokerConfiguration {

        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer("apache/kafka:4.2.2").withStartupTimeout(Duration.ofSeconds(90));
        }

        @Bean
        NewTopic dispatchTopic(@Value("${notification.outbox.kafka.topic}") String topic) {
            return new NewTopic(topic, 1, (short) 1);
        }
    }
}
