package me.hanhyur.notify.api.notification.integration;

import me.hanhyur.notify.api.notification.domain.NotificationDeliveryResultRepository;
import me.hanhyur.notify.api.notification.domain.NotificationDispatchClaimRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublicationRepository;
import me.hanhyur.notify.api.notification.domain.ProviderResult;
import me.hanhyur.notify.api.support.PostgresTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
class OperatorNotificationQueryIntegrationTest {

    private static final String NOTIFICATIONS = "/api/v1/notifications";
    private static final String DASHBOARD = "/api/v1/dashboard";
    private static final Instant NOW = Instant.parse("2026-04-30T15:30:00Z");
    private static final UUID OPERATOR = UUID.randomUUID();
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String PASSWORD_HASH = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD);
    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String RECIPIENT = "sample@example.com";
    private static final String REASON = "공급자 설정을 고친 뒤 다시 접수했습니다.";
    private static final String EVIDENCE = "공급자 조회 결과를 통해 접수되지 않았음을 확인했습니다.";

    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private JsonMapper json;
    @Autowired private NotificationOutboxPublicationRepository outbox;
    @Autowired private NotificationDispatchClaimRepository claims;
    @Autowired private NotificationDeliveryResultRepository results;
    @MockitoBean private Clock clock;

    private final HttpClient browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry settings) {
        settings.add("notification.auth.operators.orders.operator-id", OPERATOR::toString);
        settings.add("notification.auth.operators.orders.display-name", () -> "orders");
        settings.add("notification.auth.operators.orders.password-hash", () -> PASSWORD_HASH);
        settings.add("notification.integration.clients.orders", () -> API_KEY);
    }

    @BeforeEach
    void fixTime() {
        when(clock.instant()).thenReturn(NOW);
    }

    @AfterEach
    void cleanUp() {
        browser.close();
        jdbc.sql("truncate table notifications cascade").update();
    }

    @Test
    void allReadRoutesRequireAnOperatorSessionEvenWithAValidExternalKey() throws Exception {
        String one = NOTIFICATIONS + "/" + UUID.randomUUID();
        for (String path : List.of(DASHBOARD, NOTIFICATIONS, one, one + "/attempts", one + "/actions")) {
            var response = send(request(path).header("X-API-Key", API_KEY).GET().build());
            problem(response, 401, "UNAUTHENTICATED");
        }
    }

    @Test
    void dashboardsCountTheCreationWindowButMeasureTheEntireCurrentQueue() throws Exception {
        login();
        Instant start = NOW.minus(Duration.ofDays(1));
        var sending = notification(1, "orders", "EMAIL", "SENDING", start.minusSeconds(1));
        attempt(sending, id(21), id(31), 1, start.minusSeconds(1), "REJECTED");
        attempt(sending, id(22), sending.dispatch(), 2, NOW.minusSeconds(76), "STARTED");
        var queued = notification(2, "orders", "SMS", "QUEUED", start);
        dispatch(queued, id(32), start);
        dispatch(queued, queued.dispatch(), NOW.minusSeconds(42));
        var retry = notification(3, "orders", "EMAIL", "RETRY_WAIT", NOW.minusSeconds(20));
        dispatch(retry, retry.dispatch(), NOW.plusSeconds(60));
        notification(4, "orders", "EMAIL", "ACCEPTED", NOW);
        notification(5, "orders", "EMAIL", "UNKNOWN", start.plusSeconds(1));

        var dashboard = ok(DASHBOARD + "?from=" + start + "&to=" + NOW);
        assertThat(dashboard.propertyNames()).containsExactlyInAnyOrder("window", "countsByStatus",
                "totalCreated", "pendingCount", "oldestDueLagSeconds", "generatedAt");
        assertThat(dashboard.get("window").get("from").asString()).isEqualTo(start.toString());
        assertThat(dashboard.get("window").get("to").asString()).isEqualTo(NOW.toString());
        assertThat(dashboard.get("generatedAt").asString()).isEqualTo(NOW.toString());
        var counts = dashboard.get("countsByStatus");
        assertThat(counts.propertyNames()).containsExactlyInAnyOrder("SCHEDULED", "QUEUED", "SENDING",
                "ACCEPTED", "RETRY_WAIT", "FAILED", "UNKNOWN");
        assertThat(counts.get("QUEUED").asInt()).isOne();
        assertThat(counts.get("RETRY_WAIT").asInt()).isOne();
        assertThat(counts.get("UNKNOWN").asInt()).isOne();
        for (String status : List.of("SENDING", "SCHEDULED", "ACCEPTED", "FAILED")) {
            assertThat(counts.get(status).asInt()).isZero();
        }
        assertThat(dashboard.get("totalCreated").asInt()).isEqualTo(3);
        assertThat(dashboard.get("pendingCount").asInt()).isEqualTo(3);
        assertThat(dashboard.get("oldestDueLagSeconds").asInt()).isEqualTo(76);
        var narrow = ok(DASHBOARD + "?from=" + start + "&to=" + start.plusNanos(1));
        assertThat(narrow.get("totalCreated").asInt()).isOne();
    }

    @Test
    void futureRetriesArePendingWithZeroLagAndHaveAMaskedScheduledRetryInTheList() throws Exception {
        login();
        var dashboard = ok(DASHBOARD);
        assertThat(dashboard.get("window").get("from").asString()).isEqualTo(NOW.minus(Duration.ofDays(7)).toString());
        assertThat(dashboard.get("totalCreated").asInt()).isZero();
        assertThat(dashboard.get("pendingCount").asInt()).isZero();
        assertThat(dashboard.get("oldestDueLagSeconds").asInt()).isZero();

        var retry = notification(1, "orders", "EMAIL", "RETRY_WAIT", NOW.minusSeconds(2));
        dispatch(retry, retry.dispatch(), NOW.plusSeconds(60));
        dashboard = ok(DASHBOARD);
        assertThat(dashboard.get("pendingCount").asInt()).isOne();
        assertThat(dashboard.get("oldestDueLagSeconds").asInt()).isZero();
        var item = ok(NOTIFICATIONS + "?view=OPEN&status=QUEUED,SENDING,RETRY_WAIT").get("items").get(0);
        assertThat(item.get("nextAttemptAt").asString()).isEqualTo(NOW.plusSeconds(60).toString());
        assertThat(item.get("recipientMasked").asString()).isEqualTo("s***@example.com");
    }

    @Test
    void defaultHistoryUsesSeoulCalendarMonthsAndKeepsTheOriginalWindowAcrossPages() throws Exception {
        login();
        Instant start = Instant.parse("2026-01-31T15:30:00Z");
        notification(1, "orders", "EMAIL", "QUEUED", start.minusNanos(1000));
        notification(2, "orders", "EMAIL", "FAILED", start);
        notification(3, "orders", "EMAIL", "UNKNOWN", start);
        notification(4, "orders", "EMAIL", "ACCEPTED", NOW);

        String filters = NOTIFICATIONS + "?customerId=orders&channel=EMAIL&size=1";
        var first = ok(filters);
        var item = first.get("items").get(0);
        assertThat(item.get("notificationId").asString()).isEqualTo(id(3).toString());
        assertThat(item.get("allowedActions").get(0).asString()).isEqualTo("RESOLVE_UNKNOWN");
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("notificationId", "customerId", "channel",
                "recipientMasked", "status", "createdAt", "updatedAt", "scheduledAt", "nextAttemptAt",
                "acceptedAt", "completedAt", "allowedActions");
        String cursor = next(first);
        String altered = cursor.substring(0, cursor.length() - 1) + (cursor.endsWith("A") ? "Q" : "A");
        problem(get(filters + "&cursor=" + altered), 400, "INVALID_REQUEST");
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        var second = ok(filters + "&cursor=" + cursor);
        assertThat(second.get("items").size()).isOne();
        assertThat(second.get("items").get(0).get("notificationId").asString()).isEqualTo(id(2).toString());
        assertThat(second.get("items").get(0).get("allowedActions").get(0).asString()).isEqualTo("RETRY");
        end(second);
        for (String changed : List.of(filters.replace("orders", "other"), filters.replace("EMAIL", "SMS"),
                filters + "&status=UNKNOWN", filters + "&view=OPEN",
                filters + "&from=" + NOW.minusSeconds(60) + "&to=" + NOW)) {
            problem(get(changed + "&cursor=" + cursor), 400, "INVALID_REQUEST");
        }
    }

    @Test
    void openSearchUsesAllThreeFiltersWithoutAnAgeCutoff() throws Exception {
        login();
        Instant old = NOW.minus(Duration.ofDays(130));
        notification(1, "orders", "SMS", "UNKNOWN", old);
        notification(2, "orders", "SMS", "FAILED", old);
        notification(3, "returns", "SMS", "UNKNOWN", old);
        notification(4, "orders", "EMAIL", "UNKNOWN", old);
        notification(5, "orders", "SMS", "QUEUED", old);
        var page = ok(NOTIFICATIONS + "?view=OPEN&channel=SMS&customerId=orders&status=UNKNOWN,SENDING");
        assertThat(page.get("items").size()).isOne();
        var item = page.get("items").get(0);
        assertThat(item.get("notificationId").asString()).isEqualTo(id(1).toString());
        assertThat(item.get("recipientMasked").asString()).isEqualTo("***1234");
        assertThat(item.get("createdAt").asString()).isEqualTo(old.toString());
        end(page);
        var first = ok(NOTIFICATIONS + "?view=OPEN&status=QUEUED,UNKNOWN&size=1");
        var rest = ok(NOTIFICATIONS + "?view=OPEN&status=UNKNOWN,QUEUED&size=10&cursor=" + next(first));
        assertThat(rest.get("items").size()).isEqualTo(3);
        end(rest);
    }

    @Test
    void invalidInputDistinguishesMalformedValuesFromInvalidConditions() throws Exception {
        login();
        for (String path : List.of(NOTIFICATIONS + "/bad-id", NOTIFICATIONS + "?cursor=not-a-cursor",
                NOTIFICATIONS + "?size=two", NOTIFICATIONS + "?view=ARCHIVE", NOTIFICATIONS + "?status=BOUNCED",
                DASHBOARD + "?from=yesterday&to=" + NOW)) {
            problem(get(path), 400, "INVALID_REQUEST");
        }
        for (String path : List.of(NOTIFICATIONS + "?size=0", NOTIFICATIONS + "?size=101",
                NOTIFICATIONS + "?size=2147483648", NOTIFICATIONS + "?customerId=a%00b",
                NOTIFICATIONS + "?customerId=" + encoded("🔔".repeat(101)),
                NOTIFICATIONS + "?from=" + NOW, NOTIFICATIONS + "?view=OPEN&status=SENDING,FAILED",
                NOTIFICATIONS + "?view=OPEN&from=" + NOW.minusSeconds(1) + "&to=" + NOW,
                NOTIFICATIONS + "?from=2025-12-01T00:00:00Z&to=" + NOW,
                NOTIFICATIONS + "?from=" + NOW.minusSeconds(1) + "&to=" + NOW.plusSeconds(1),
                DASHBOARD + "?to=" + NOW, DASHBOARD + "?from=" + NOW + "&to=" + NOW,
                DASHBOARD + "?from=" + NOW.minus(Duration.ofDays(31)).minusSeconds(1) + "&to=" + NOW)) {
            problem(get(path), 422, "VALIDATION_FAILED");
        }
        var withOffset = ok(DASHBOARD + "?from=" + encoded("2026-04-01T00:00:00+09:00") + "&to=" + NOW);
        assertThat(withOffset.get("window").get("from").asString()).isEqualTo("2026-03-31T15:00:00Z");
    }

    @Test
    void registeredDetailsAndWorkerAttemptsUseTheSameNotificationWithoutLeakingTheRecipient() throws Exception {
        login();
        var registered = register();
        String path = NOTIFICATIONS + "/" + registered.get("notificationId").asString();
        assertThat(ok(path)).isEqualTo(registered);
        for (String child : List.of("/attempts", "/actions")) {
            var empty = ok(path + child);
            assertThat(empty.get("items").size()).isZero();
            end(empty);
        }
        var first = outbox.findPending(NOW, 1).get(0);
        assertThat(outbox.markPublished(first.eventId(), NOW)).isTrue();
        assertThat(claims.tryClaim(first, NOW)).isTrue();
        results.finish(first, new ProviderResult(ProviderResult.Outcome.TRANSIENT_REJECTION, null), NOW);
        var retry = outbox.findPending(NOW.plusSeconds(60), 1).get(0);
        assertThat(claims.tryClaim(retry, NOW.plusSeconds(60))).isTrue();

        var attempts = ok(path + "/attempts?size=1");
        var current = attempts.get("items").get(0);
        assertThat(current.propertyNames()).containsExactlyInAnyOrder("attemptId", "notificationId", "dispatchId",
                "attemptNo", "status", "failureCategory", "startedAt", "finishedAt", "providerReference",
                "errorCode", "errorSummary");
        assertThat(current.get("dispatchId").asString()).isEqualTo(retry.dispatchId().toString());
        assertThat(current.get("attemptNo").asInt()).isEqualTo(2);
        assertThat(current.get("status").asString()).isEqualTo("STARTED");
        assertThat(current.get("finishedAt").isNull()).isTrue();
        String cursor = next(attempts);
        var older = ok(path + "/attempts?size=1&cursor=" + cursor);
        var rejected = older.get("items").get(0);
        assertThat(rejected.get("attemptNo").asInt()).isOne();
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("failureCategory").asString()).isEqualTo("TRANSIENT_CONFIRMED_NOT_ACCEPTED");
        assertThat(rejected.get("finishedAt").asString()).isEqualTo(NOW.toString());
        end(older);
        String reference = "sandbox-" + UUID.randomUUID();
        results.finish(retry, new ProviderResult(ProviderResult.Outcome.ACCEPTED, reference), NOW.plusSeconds(61));
        var last = ok(path + "/attempts").get("items").get(0);
        assertThat(last.get("providerReference").asString()).isEqualTo(reference);
        assertThat(last.get("finishedAt").asString()).isEqualTo(NOW.plusSeconds(61).toString());
        assertThat(ok(path).get("acceptedAt").asString()).isEqualTo(NOW.plusSeconds(61).toString());

        var other = notification(1, "other", "EMAIL", "QUEUED", NOW);
        problem(get(NOTIFICATIONS + "/" + other.id() + "/attempts?cursor=" + cursor), 400, "INVALID_REQUEST");
        problem(get(path + "/actions?cursor=" + cursor), 400, "INVALID_REQUEST");
    }

    @Test
    void auditPaginationUsesIdForTiedTimesAndLeavesAttemptHistorySeparate() throws Exception {
        login();
        var target = notification(1, "orders", "EMAIL", "UNKNOWN", NOW);
        var other = notification(2, "orders", "EMAIL", "FAILED", NOW);
        action(target, id(5), false);
        action(target, id(7), true);
        action(other, id(8), false);
        String path = NOTIFICATIONS + "/" + target.id();
        var actions = ok(path + "/actions?size=1");
        var resolved = actions.get("items").get(0);
        assertThat(resolved.propertyNames()).containsExactlyInAnyOrder("id", "operatorId", "action", "reason",
                "evidence", "resolution", "previousStatus", "resultingStatus", "generation", "createdAt");
        assertThat(resolved.get("id").asString()).isEqualTo(id(7).toString());
        assertThat(resolved.get("operatorId").asString()).isEqualTo(OPERATOR.toString());
        assertThat(resolved.get("evidence").asString()).isEqualTo(EVIDENCE);
        assertThat(resolved.get("resolution").asString()).isEqualTo("NOT_SENT");
        assertThat(resolved.get("reason").isNull()).isTrue();
        String cursor = next(actions);
        var older = ok(path + "/actions?size=1&cursor=" + cursor);
        var retried = older.get("items").get(0);
        assertThat(retried.get("id").asString()).isEqualTo(id(5).toString());
        assertThat(retried.get("reason").asString()).isEqualTo(REASON);
        assertThat(retried.get("evidence").isNull()).isTrue();
        end(older);
        assertThat(ok(path + "/attempts").get("items").size()).isZero();
        assertThat(ok(path).get("allowedActions").get(0).asString()).isEqualTo("RESOLVE_UNKNOWN");
        problem(get(NOTIFICATIONS + "/" + other.id() + "/actions?cursor=" + cursor), 400, "INVALID_REQUEST");
    }

    @Test
    void allNotificationReadsReturnNotFoundForAnUnknownUuid() throws Exception {
        login();
        String path = NOTIFICATIONS + "/" + UUID.randomUUID();
        for (String suffix : List.of("", "/attempts", "/actions")) {
            problem(get(path + suffix), 404, "NOT_FOUND");
        }
    }

    @Test
    void aDatabaseReadFailureReturnsATraceableServiceUnavailable() throws Exception {
        login();
        var target = notification(1, "orders", "EMAIL", "QUEUED", NOW);
        jdbc.sql("alter table notification_operator_actions rename to temporarily_unavailable_actions").update();
        try {
            problem(get(NOTIFICATIONS + "/" + target.id() + "/actions"), 503, "PERSISTENCE_UNAVAILABLE");
        } finally {
            jdbc.sql("alter table temporarily_unavailable_actions rename to notification_operator_actions").update();
        }
    }

    private void login() throws Exception {
        String token = ok("/api/v1/auth/csrf").get("token").asString();
        String credentials = json.writeValueAsString(Map.of("username", "orders", "password", PASSWORD));
        var response = send(request("/api/v1/auth/login").header("X-CSRF-TOKEN", token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(credentials)).build());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private JsonNode register() throws Exception {
        String token = ok("/api/v1/auth/csrf").get("token").asString();
        String body = json.writeValueAsString(Map.of("customerId", "orders", "channel", "EMAIL",
                "recipient", RECIPIENT, "title", "주문 안내", "content", "주문이 접수되었습니다."));
        var response = send(request(NOTIFICATIONS).header("X-CSRF-TOKEN", token)
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body());
    }

    private static String encoded(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static String next(JsonNode page) {
        assertThat(page.propertyNames()).containsExactlyInAnyOrder("items", "hasNext", "nextCursor");
        assertThat(page.get("hasNext").asBoolean()).isTrue();
        assertThat(page.get("nextCursor").asString()).isNotBlank();
        return encoded(page.get("nextCursor").asString());
    }

    private static void end(JsonNode page) {
        assertThat(page.get("hasNext").asBoolean()).isFalse();
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.uri().getPath()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        var body = json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("requestId").asString()).isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(body.get("instance").asString()).isEqualTo(response.uri().getPath());
    }

    private JsonNode ok(String path) throws Exception {
        var response = get(path);
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(request(path).GET().build());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        var response = browser.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("no-store");
        UUID.fromString(response.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(response.body()).doesNotContain(RECIPIENT, PASSWORD, PASSWORD_HASH, API_KEY);
        return response;
    }

    private static UUID id(long number) {
        return new UUID(0, number);
    }

    private record Row(UUID id, UUID dispatch, Instant created) {}

    private Row notification(long number, String customer, String channel, String status, Instant created) {
        var row = new Row(id(number), UUID.randomUUID(), created);
        jdbc.sql("""
                insert into notifications (notification_id, current_dispatch_id, customer_id, channel, recipient,
                    title, content, status, created_at, updated_at)
                values (:id, :dispatch, :customer, :channel, :recipient, '제목', '본문', :status, :time, :time)
                """).param("id", row.id()).param("dispatch", row.dispatch()).param("customer", customer)
                .param("channel", channel).param("recipient", channel.equals("EMAIL") ? RECIPIENT : "01055551234")
                .param("status", status).param("time", created.atOffset(ZoneOffset.UTC)).update();
        return row;
    }

    private void dispatch(Row row, UUID dispatch, Instant available) {
        jdbc.sql("""
                insert into notification_outbox (event_id, notification_id, dispatch_id, generation, occurred_at, available_at)
                values (gen_random_uuid(), :id, :dispatch, 1, :created, :available)
                """).param("id", row.id()).param("dispatch", dispatch).param("created", row.created().atOffset(ZoneOffset.UTC))
                .param("available", available.atOffset(ZoneOffset.UTC)).update();
    }

    private void attempt(Row row, UUID attempt, UUID dispatch, int number, Instant started, String status) {
        jdbc.sql("""
                insert into notification_delivery_attempts (attempt_id, notification_id, dispatch_id, attempt_no, status, started_at)
                values (:attempt, :id, :dispatch, :number, :status, :started)
                """).param("attempt", attempt).param("id", row.id()).param("dispatch", dispatch)
                .param("number", number).param("status", status).param("started", started.atOffset(ZoneOffset.UTC)).update();
    }

    private void action(Row row, UUID actionId, boolean resolution) {
        jdbc.sql("""
                insert into notification_operator_actions (id, notification_id, operator_id, action, reason, evidence,
                    resolution, previous_status, resulting_status, generation, created_at)
                values (:id, :target, :operator, :action, :reason, :evidence, :resolution, :previous, :result, 2, :time)
                """).param("id", actionId).param("target", row.id()).param("operator", OPERATOR)
                .param("action", resolution ? "RESOLVE_UNKNOWN" : "RETRY").param("reason", resolution ? null : REASON)
                .param("evidence", resolution ? EVIDENCE : null).param("resolution", resolution ? "NOT_SENT" : null)
                .param("previous", resolution ? "UNKNOWN" : "FAILED").param("result", resolution ? "FAILED" : "QUEUED")
                .param("time", NOW.atOffset(ZoneOffset.UTC)).update();
    }
}
