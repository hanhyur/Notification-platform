package me.hanhyur.notify.api.notification.infrastructure;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.sun.net.httpserver.HttpServer;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "notification.dispatch.consumer.enabled=true",
        "notification.dispatch.consumer.group-id=dispatch-claim-integration",
        "notification.dispatch.consumer.concurrency=2",
        "notification.dispatch.pool.workers=2",
        "notification.dispatch.pool.queue-capacity=1",
        "notification.dispatch.provider.response-timeout=20s",
        "notification.outbox.kafka.topic=dispatch-claim-test"
})
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class, KafkaDispatchClaimIntegrationTest.BrokerConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class KafkaDispatchClaimIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final Set<String> LIFECYCLE_STAGES = Set.of(
            "notification_registered", "outbox_publication_recorded", "dispatch_claimed", "delivery_result_recorded");

    private final AtomicInteger requests = new AtomicInteger();
    private final CompletableFuture<JsonNode> received = new CompletableFuture<>();
    private final CountDownLatch allowResponse = new CountDownLatch(1);
    private final CountDownLatch scheduledForRedelivery = new CountDownLatch(1);
    private final Logger notificationLogger = (Logger) LoggerFactory.getLogger("me.hanhyur.notify.api.notification");
    private final List<LifecycleLog> lifecycleLogs = new CopyOnWriteArrayList<>();
    private volatile UUID expectedDispatchId;
    private AppenderBase<ILoggingEvent> lifecycleAppender;

    @Autowired
    private NotificationRegistrationService registration;
    @Autowired
    private NotificationOutboxPublicationService publication;
    @Autowired
    private NotificationOutboxPublicationRepository outbox;
    @Autowired
    private NotificationOutboxPublisher publisher;
    @Autowired
    private KafkaOperations<String, String> kafkaOperations;
    @Autowired
    private KafkaListenerEndpointRegistry listeners;
    @Autowired
    private DispatchWorkerPool workers;
    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private KafkaContainer kafka;
    @Autowired
    private Sandbox sandbox;
    @Autowired
    private JsonMapper json;
    @Value("${notification.outbox.kafka.topic}")
    private String topic;
    @Value("${notification.dispatch.consumer.group-id}")
    private String groupId;

    @AfterEach
    void releaseLifecycleLogs() {
        notificationLogger.detachAppender(lifecycleAppender);
        lifecycleAppender.stop();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationStatus.class, names = {"ACCEPTED", "UNKNOWN"})
    @Timeout(45)
    void persistsTheProviderResultAndCommitsBothRecordsAfterOnePost(NotificationStatus expected) throws Exception {
        Notification registered = registration.register(new RegisterNotificationCommand(
                "customer-1001", Channel.EMAIL, "sample@example.com", "주문 안내", "주문이 접수되었습니다.", null));
        UUID id = registered.notificationId();
        NotificationOutboxEvent event = outbox.findPending(Instant.now(), 1).getFirst();
        assertEquals(id, event.notificationId());
        expectedDispatchId = event.dispatchId();
        assertStage("notification_registered", Level.INFO, event);

        String reference = "sandbox-" + event.dispatchId();
        configureProviderResponse(expected, reference);

        Attempt started;
        try {
            assertEquals(1, publication.publishPending(1));
            JsonNode request = received.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(event.dispatchId().toString(), request.get("dispatchId").asString());
            assertEquals(registered.recipient(), request.get("recipient").asString());
            Notification claimed = notifications.findById(id).orElseThrow();
            started = singleAttempt(id);
            assertEquals(NotificationStatus.SENDING, claimed.status());
            assertEquals(1L, claimed.version());
            assertEquals("STARTED", started.status());
            assertEquals(claimed.updatedAt(), started.startedAt());
            assertNull(started.finishedAt());
            assertStage("outbox_publication_recorded", Level.INFO, event);
            assertTrue(assertStage("dispatch_claimed", Level.INFO, event).committedClaim());
            assertEquals(3, lifecycleLogs.size());
        } finally {
            allowResponse.countDown();
        }

        await("공급자 응답과 발송 이력 저장").atMost(WAIT).until(() ->
                notifications.findById(id).orElseThrow().status() == expected);
        Notification result = notifications.findById(id).orElseThrow();
        Attempt finished = singleAttempt(id);
        assertAll(
                () -> assertEquals(2L, result.version()),
                () -> assertEquals(started.id(), finished.id()),
                () -> assertEquals(event.dispatchId(), finished.dispatchId()),
                () -> assertEquals(1, finished.number()),
                () -> assertEquals(expected.name(), finished.status()),
                () -> assertEquals(result.updatedAt(), finished.finishedAt()),
                () -> assertEquals(expected == NotificationStatus.ACCEPTED ? reference : null, finished.reference()),
                () -> assertEquals(expected == NotificationStatus.UNKNOWN ? "AMBIGUOUS" : null, finished.failureCategory())
        );

        publisher.publish(event);
        awaitBothCommits();
        Level resultLevel = expected == NotificationStatus.ACCEPTED ? Level.INFO : Level.WARN;
        assertThat(assertStage("delivery_result_recorded", resultLevel, event).fields()).contains("status=" + expected);
        assertEquals(4, lifecycleLogs.size());
        assertEquals(result, notifications.findById(id).orElseThrow());
        assertEquals(finished, singleAttempt(id));
        assertEquals(1, requests.get());
        assertEquals(1L, jdbc.sql("select count(*) from notification_outbox where notification_id = :id")
                .param("id", id).query(Long.class).single());
    }

    @Test
    @Timeout(45)
    void separatePartitionsDeliverTogetherAndOnlyCommitFinishedWork() throws Exception {
        var command = new RegisterNotificationCommand(
                "customer-1001", Channel.EMAIL, "sample@example.com", "주문 안내", "주문이 접수되었습니다.", null);
        registration.register(command);
        registration.register(command);
        var events = outbox.findPending(Instant.now(), 2);
        assertThat(events).hasSize(2);
        var first = events.getFirst();
        var second = events.getLast();
        var bothAtProvider = new CountDownLatch(2);
        var releaseFirst = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var gates = Map.of(first.dispatchId(), releaseFirst, second.dispatchId(), releaseSecond);
        sandbox.server().createContext("/deliveries", exchange -> {
            try (exchange) {
                UUID dispatchId = UUID.fromString(json.readTree(exchange.getRequestBody()).get("dispatchId").asString());
                requests.incrementAndGet();
                bothAtProvider.countDown();
                try {
                    if (!gates.get(dispatchId).await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                        return;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                byte[] body = json.writeValueAsBytes(Map.of("status", "ACCEPTED", "reference", "sandbox-" + dispatchId));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, body.length);
                exchange.getResponseBody().write(body);
            }
        });

        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            var container = listeners.getListenerContainer("notificationDispatchClaim");
            assertNotNull(container);
            await("두 소비자에 각각 하나의 파티션 배정").atMost(WAIT).untilAsserted(() -> {
                var assigned = container.getAssignmentsByClientId();
                assertThat(assigned).hasSize(2);
                assertThat(assigned.values()).allSatisfy(partitions -> assertThat(partitions).hasSize(1));
                assertThat(container.getAssignedPartitions()).containsExactlyInAnyOrder(
                        new TopicPartition(topic, 0), new TopicPartition(topic, 1));
            });
            sendToPartition(first, 0);
            sendToPartition(second, 1);
            assertTrue(bothAtProvider.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "서로 다른 두 발송을 동시에 처리해야 합니다.");
            assertEquals("STARTED", singleAttempt(first.notificationId()).status());
            assertEquals("STARTED", singleAttempt(second.notificationId()).status());
            releaseFirst.countDown();
            await().atMost(WAIT).until(() -> committedOffset(admin, 0) == 1);
            assertEquals("ACCEPTED", singleAttempt(first.notificationId()).status());
            assertEquals(0L, committedOffset(admin, 1));
            assertEquals("STARTED", singleAttempt(second.notificationId()).status());
            releaseSecond.countDown();
            sendToPartition(second, 1);
            await().atMost(WAIT).until(() -> committedOffset(admin, 1) == 2);
            assertEquals("ACCEPTED", singleAttempt(second.notificationId()).status());
            assertEquals(2, requests.get());
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
        }
    }

    private void sendToPartition(NotificationOutboxEvent event, int partition) throws Exception {
        kafkaOperations.send(topic, partition, event.notificationId().toString(), json.writeValueAsString(event))
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private long committedOffset(Admin admin, int partition) throws Exception {
        var offsets = admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
        var committed = offsets.get(new TopicPartition(topic, partition));
        return committed == null ? 0L : committed.offset();
    }

    @Test
    @Timeout(45)
    void rejectedRecordStaysUnclaimedAndIsRedeliveredWhenThePoolHasCapacity() throws Exception {
        var saved = registration.register(new RegisterNotificationCommand(
                "customer-1001", Channel.EMAIL, "sample@example.com", "주문 안내", "주문이 접수되었습니다.", null));
        var event = outbox.findPending(Instant.now(), 1).getFirst();
        expectedDispatchId = event.dispatchId();
        configureProviderResponse(NotificationStatus.ACCEPTED, "sandbox-" + event.dispatchId());
        allowResponse.countDown();
        var bothBusy = new CountDownLatch(2);
        var releaseWorkers = new CountDownLatch(1);
        Runnable occupyWorker = () -> {
            bothBusy.countDown();
            try {
                assertTrue(releaseWorkers.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            workers.execute(occupyWorker);
            workers.execute(occupyWorker);
            assertTrue(bothBusy.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            workers.execute(() -> {});
            sendToPartition(event, 0);
            assertTrue(scheduledForRedelivery.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "포화된 작업은 Kafka 재처리 대상으로 남아야 합니다.");
            assertEquals(0L, committedOffset(admin, 0));
            assertEquals(NotificationStatus.QUEUED, notifications.findById(saved.notificationId()).orElseThrow().status());
            assertEquals(0, requests.get());
            releaseWorkers.countDown();
            await().atMost(WAIT).until(() -> committedOffset(admin, 0) == 1L);
            assertEquals("ACCEPTED", singleAttempt(saved.notificationId()).status());
            assertEquals(1, requests.get());
        } finally {
            releaseWorkers.countDown();
        }
    }

    @BeforeEach
    void observeLifecycleLogs() {
        var reader = new TransactionTemplate(transactions);
        reader.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        lifecycleAppender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent entry) {
                if (entry.getFormattedMessage().startsWith("발송 이벤트 재시도 예정.")) {
                    scheduledForRedelivery.countDown();
                }
                var fields = List.of(entry.getFormattedMessage().split(" "));
                String stage = fields.getFirst();
                if (LIFECYCLE_STAGES.contains(stage)) {
                    boolean committedClaim = stage.equals("dispatch_claimed") && expectedDispatchId != null && reader.execute(tx ->
                            jdbc.sql("""
                                    SELECT EXISTS (
                                        SELECT 1 FROM notifications n
                                        JOIN notification_delivery_attempts a ON a.notification_id = n.notification_id
                                        WHERE a.dispatch_id = :dispatch AND n.status = 'SENDING' AND a.status = 'STARTED'
                                    )
                                    """).param("dispatch", expectedDispatchId).query(Boolean.class).single());
                    lifecycleLogs.add(new LifecycleLog(stage, entry.getLevel(), fields, committedClaim));
                }
            }
        };
        lifecycleAppender.start();
        notificationLogger.addAppender(lifecycleAppender);
    }

    private LifecycleLog assertStage(String stage, Level level, NotificationOutboxEvent event) {
        var matching = lifecycleLogs.stream().filter(log -> log.stage().equals(stage)).toList();
        assertThat(matching).as(stage).hasSize(1);
        LifecycleLog log = matching.getFirst();
        assertEquals(level, log.level());
        assertThat(log.fields()).contains("notificationId=" + event.notificationId(),
                "dispatchId=" + event.dispatchId(), "eventId=" + event.eventId());
        return log;
    }

    private record LifecycleLog(String stage, Level level, List<String> fields, boolean committedClaim) {}

    private void awaitBothCommits() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            await("중복 이벤트까지 소비 오프셋 커밋").atMost(WAIT).until(() -> {
                var offsets = admin.listConsumerGroupOffsets(groupId)
                        .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                return offsets.values().stream().mapToLong(OffsetAndMetadata::offset).sum() == 2L;
            });
        }
    }

    private void configureProviderResponse(NotificationStatus expected, String reference) {
        sandbox.server().createContext("/deliveries", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                received.complete(json.readTree(exchange.getRequestBody()));
                try {
                    if (!allowResponse.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                        return;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (expected == NotificationStatus.ACCEPTED) {
                    byte[] body = json.writeValueAsBytes(Map.of("status", "ACCEPTED", "reference", reference));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(202, body.length);
                    exchange.getResponseBody().write(body);
                } else {
                    exchange.sendResponseHeaders(503, -1);
                }
            }
        });
    }

    private Attempt singleAttempt(UUID notificationId) {
        return jdbc.sql("""
                SELECT attempt_id, dispatch_id, attempt_no, status, started_at, finished_at, provider_reference, failure_category
                FROM notification_delivery_attempts WHERE notification_id = :id
                """).param("id", notificationId)
                .query((rs, row) -> new Attempt(
                        rs.getObject("attempt_id", UUID.class), rs.getObject("dispatch_id", UUID.class),
                        rs.getInt("attempt_no"), rs.getString("status"),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                        rs.getString("provider_reference"), rs.getString("failure_category"))).single();
    }

    private record Attempt(UUID id, UUID dispatchId, int number, String status, Instant startedAt,
                           Instant finishedAt, String reference, String failureCategory) {}

    private record Sandbox(HttpServer server) implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BrokerConfiguration {

        @Bean
        Sandbox sandbox() throws IOException {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Thread::startVirtualThread);
            server.start();
            return new Sandbox(server);
        }

        @Bean
        DynamicPropertyRegistrar providerEndpoint(Sandbox sandbox) {
            return registry -> registry.add("notification.dispatch.provider.endpoint",
                    () -> "http://127.0.0.1:" + sandbox.server().getAddress().getPort() + "/deliveries");
        }

        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer("apache/kafka:4.2.2").withStartupTimeout(Duration.ofSeconds(90));
        }

        @Bean
        NewTopic dispatchTopic(@Value("${notification.outbox.kafka.topic}") String topic) {
            return new NewTopic(topic, 2, (short) 1);
        }
    }
}
