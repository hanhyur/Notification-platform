package me.hanhyur.notify.api.notification.integration;

import me.hanhyur.notify.api.common.database.DatabaseRole;
import me.hanhyur.notify.api.common.database.SlowQueryAlert;
import me.hanhyur.notify.api.common.database.SlowQueryAlertSink;
import me.hanhyur.notify.api.notification.application.NotificationRegistrationService;
import me.hanhyur.notify.api.notification.application.RegisterNotificationCommand;
import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.NotificationDeliveryResultRepository;
import me.hanhyur.notify.api.notification.domain.NotificationDispatchClaimRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublicationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationRepository;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import me.hanhyur.notify.api.notification.domain.ProviderResult;
import me.hanhyur.notify.api.support.ReplicatedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "notification.database.slow-query-threshold=200ms")
@ActiveProfiles("test")
@Import(DatabaseRoutingIntegrationTest.Alerts.class)
@DirtiesContext
class DatabaseRoutingIntegrationTest {

    private static final ReplicatedPostgres DATABASE = new ReplicatedPostgres();
    private static final String BASE = "/api/v1/notifications";
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String API_KEY = UUID.randomUUID().toString();
    private static final Duration WAIT = Duration.ofSeconds(20);

    @LocalServerPort private int port;
    @Autowired private JsonMapper json;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private NotificationRepository notifications;
    @Autowired private NotificationOutboxPublicationRepository outbox;
    @Autowired private NotificationDispatchClaimRepository claims;
    @Autowired private NotificationDeliveryResultRepository results;
    @Autowired private NotificationRegistrationService registrations;
    @Autowired private RecordingSink alerts;

    @DynamicPropertySource
    static void connectionSettings(DynamicPropertyRegistry settings) {
        DATABASE.start();
        settings.add("spring.datasource.url", DATABASE::primaryUrl);
        settings.add("spring.datasource.username", DATABASE::username);
        settings.add("spring.datasource.password", DATABASE::password);
        settings.add("notification.database.replica.url", DATABASE::replicaUrl);
        UUID operator = UUID.randomUUID();
        String hash = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD);
        settings.add("notification.auth.operators.orders.operator-id", operator::toString);
        settings.add("notification.auth.operators.orders.display-name", () -> "orders");
        settings.add("notification.auth.operators.orders.password-hash", () -> hash);
        settings.add("notification.integration.clients.orders", () -> API_KEY);
    }

    @AfterAll
    static void stopDatabases() {
        DATABASE.close();
    }

    @Test
    void readOnlyTransactionsUseTheStandbyAndJoinedReadsStayOnTheWriter() {
        assertThat(onStandby()).isFalse();
        var read = readOnlyTransaction();
        read.executeWithoutResult(status -> assertThat(onStandby()).isTrue());
        var write = new TransactionTemplate(transactions);
        write.executeWithoutResult(outer -> {
            read.executeWithoutResult(inner -> assertThat(onStandby()).isFalse());
            assertThat(onStandby()).isFalse();
        });
        assertThat(onStandby()).isFalse();
    }

    @Test
    void aReadOnlyHintOutsideAnActualTransactionUsesTheWriter() {
        var optional = readOnlyTransaction();
        optional.setPropagationBehavior(TransactionDefinition.PROPAGATION_SUPPORTS);
        optional.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(onStandby()).isFalse();
        });
    }

    @Test
    void aPausedReplicaDelaysTheOverviewButNotRegistrationReplayOrOperatorActions() throws Exception {
        var standby = JdbcClient.create(new DriverManagerDataSource(
                DATABASE.replicaUrl(), DATABASE.username(), DATABASE.password()));
        waitForReplay(standby);
        String customer = UUID.randomUUID().toString();
        var registration = request("/integration/v1/notifications").header("X-API-Key", API_KEY)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(body(Map.of("customerId", customer, "channel", "EMAIL", "recipient", "sample@example.com",
                        "title", "주문 안내", "content", "주문이 접수되었습니다."))).build();
        try (var browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            String csrf = login(browser);
            long before = get(browser, "/api/v1/dashboard").get("totalCreated").asLong();
            standby.sql("select pg_wal_replay_pause()").query((row, n) -> true).single();
            UUID id;
            try {
                await().atMost(WAIT).until(() -> "paused".equals(standby.sql("select pg_get_wal_replay_pause_state()")
                        .query(String.class).single()));
                id = UUID.fromString(send(browser, registration, 202).get("notificationId").asString());
                String detail = BASE + "/" + id;
                var now = Instant.now();
                var event = outbox.findPending(now, 100).stream().filter(e -> e.notificationId().equals(id))
                        .findFirst().orElseThrow();
                assertThat(outbox.markPublished(event.eventId(), now)).isTrue();
                assertThat(claims.tryClaim(event, now)).isTrue();
                var claimed = notifications.findById(id).orElseThrow();
                assertThat(claimed.status()).isEqualTo(NotificationStatus.SENDING);
                assertThat(claimed.currentDispatchId()).isEqualTo(event.dispatchId());
                results.finish(event, new ProviderResult(ProviderResult.Outcome.PERMANENT_REJECTION, null), Instant.now());
                var failed = get(browser, detail);
                assertThat(failed.get("status").asString()).isEqualTo("FAILED");
                var retry = request(detail + "/retries").header("X-CSRF-TOKEN", csrf)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .POST(body(Map.of("expectedVersion", failed.get("version").asLong(),
                                "reason", "공급자 설정 변경을 확인하고 다시 접수합니다."))).build();
                var queued = send(browser, retry, 202);
                assertThat(queued.get("generation").asInt()).isEqualTo(2);
                assertThat(send(browser, retry, 202)).isEqualTo(queued);
                var replay = send(browser, registration, 202);
                assertThat(replay.get("notificationId").asString()).isEqualTo(id.toString());
                assertThat(replay.get("generation").asInt()).isEqualTo(2);
                assertThat(get(browser, detail + "/attempts").get("items").size()).isOne();
                assertThat(get(browser, detail + "/actions").get("items").size()).isOne();
                assertThat(get(browser, BASE + "?customerId=" + customer).get("items").size()).isZero();
                assertThat(get(browser, "/api/v1/dashboard").get("totalCreated").asLong()).isEqualTo(before);
            } finally {
                standby.sql("select pg_wal_replay_resume()").query((row, n) -> true).single();
            }
            waitForReplay(standby);
            var rows = get(browser, BASE + "?customerId=" + customer).get("items");
            assertThat(rows.size()).isOne();
            assertThat(rows.get(0).get("notificationId").asString()).isEqualTo(id.toString());
            assertThat(get(browser, "/api/v1/dashboard").get("totalCreated").asLong()).isEqualTo(before + 1);
        }
    }

    @Test
    void slowStatementsReportThePhysicalRoleAndRequestIdWithoutTheBoundValue() {
        String requestId = UUID.randomUUID().toString();
        String value = UUID.randomUUID().toString();
        try (var ignored = MDC.putCloseable("requestId", requestId)) {
            assertThat(delayedRead(0, value)).isFalse();
            assertThat(alerts.forRequest(requestId)).isEmpty();
            assertThat(delayedRead(0.35, value)).isFalse();
            readOnlyTransaction().executeWithoutResult(status -> assertThat(delayedRead(0.35, value)).isTrue());
        }
        var reported = alerts.forRequest(requestId);
        assertThat(reported).extracting(SlowQueryAlert::databaseRole)
                .containsExactly(DatabaseRole.PRIMARY, DatabaseRole.REPLICA);
        assertThat(reported).allSatisfy(alert -> {
            assertThat(alert.queryId()).isNotBlank();
            assertThat(alert.elapsed()).isGreaterThan(Duration.ofMillis(200));
            assertThat(alert.occurredAt()).isNotNull();
            assertThat(alert.toString()).doesNotContain(value, "pg_sleep");
        });
    }

    @Test
    void aBlockedJpaDetailQueryReportsTheIdOfItsHttpRequest() throws Exception {
        String recipient = "sample@example.com";
        var saved = registrations.register(new RegisterNotificationCommand("orders", Channel.EMAIL,
                recipient, "주문 안내", "주문이 접수되었습니다.", null));
        try (var browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
             var blocker = DriverManager.getConnection(DATABASE.primaryUrl(), DATABASE.username(), DATABASE.password());
             var lock = blocker.createStatement()) {
            login(browser);
            blocker.setAutoCommit(false);
            lock.execute("lock table notifications in access exclusive mode");
            var pending = browser.sendAsync(request(BASE + "/" + saved.notificationId()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            try {
                await().atMost(WAIT).until(() -> jdbc.sql("""
                        select exists (select from pg_locks where relation = 'notifications'::regclass
                                       and mode = 'AccessShareLock' and not granted)
                        """).query(Boolean.class).single());
                lock.execute("select pg_sleep(0.3)");
            } finally {
                blocker.rollback();
            }
            var response = pending.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            String requestId = response.headers().firstValue("X-Request-ID").orElseThrow();
            assertThat(alerts.forRequest(requestId)).anySatisfy(alert -> {
                assertThat(alert.databaseRole()).isEqualTo(DatabaseRole.PRIMARY);
                assertThat(alert.queryId()).contains("JpaNotificationHistory#detail");
                assertThat(alert.elapsed()).isGreaterThan(Duration.ofMillis(200));
                assertThat(alert.toString()).doesNotContain(recipient, saved.notificationId().toString(), "select ");
            });
        }
    }

    private boolean delayedRead(double seconds, String value) {
        return jdbc.sql("select pg_is_in_recovery() from pg_sleep(:seconds) where cast(:value as text) is not null")
                .param("seconds", seconds).param("value", value).query(Boolean.class).single();
    }

    private boolean onStandby() {
        return jdbc.sql("select pg_is_in_recovery()").query(Boolean.class).single();
    }

    private TransactionTemplate readOnlyTransaction() {
        var transaction = new TransactionTemplate(transactions);
        transaction.setReadOnly(true);
        return transaction;
    }

    private void waitForReplay(JdbcClient standby) {
        String position = jdbc.sql("select pg_current_wal_flush_lsn()::text").query(String.class).single();
        await().atMost(WAIT).until(() -> standby.sql("select pg_last_wal_replay_lsn() >= cast(:lsn as pg_lsn)")
                .param("lsn", position).query(Boolean.class).single());
    }

    private String login(HttpClient browser) throws Exception {
        String csrf = get(browser, "/api/v1/auth/csrf").get("token").asString();
        send(browser, request("/api/v1/auth/login").header("X-CSRF-TOKEN", csrf)
                .POST(body(Map.of("username", "orders", "password", PASSWORD))).build(), 200);
        return get(browser, "/api/v1/auth/csrf").get("token").asString();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(WAIT).header("Content-Type", "application/json");
    }

    private HttpRequest.BodyPublisher body(Map<String, ?> fields) {
        return HttpRequest.BodyPublishers.ofString(json.writeValueAsString(fields));
    }

    private JsonNode get(HttpClient client, String path) throws Exception {
        return send(client, request(path).GET().build(), 200);
    }

    private JsonNode send(HttpClient client, HttpRequest request, int expected) throws Exception {
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(request.uri().getPath()).isEqualTo(expected);
        return json.readTree(response.body());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Alerts {
        @Bean
        RecordingSink recordingSink() {
            return new RecordingSink();
        }
    }

    static class RecordingSink implements SlowQueryAlertSink {
        private final List<SlowQueryAlert> events = new CopyOnWriteArrayList<>();

        @Override
        public void accept(SlowQueryAlert alert) {
            events.add(alert);
        }

        List<SlowQueryAlert> forRequest(String id) {
            return events.stream().filter(event -> id.equals(event.requestId())).toList();
        }
    }
}
