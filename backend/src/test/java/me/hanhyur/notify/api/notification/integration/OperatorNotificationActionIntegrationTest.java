package me.hanhyur.notify.api.notification.integration;

import me.hanhyur.notify.api.notification.domain.NotificationDeliveryResultRepository;
import me.hanhyur.notify.api.notification.domain.NotificationDispatchClaimRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxEvent;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublicationRepository;
import me.hanhyur.notify.api.notification.domain.ProviderResult;
import me.hanhyur.notify.api.support.PostgresTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static me.hanhyur.notify.api.notification.domain.ProviderResult.Outcome.ACCEPTED;
import static me.hanhyur.notify.api.notification.domain.ProviderResult.Outcome.PERMANENT_REJECTION;
import static me.hanhyur.notify.api.notification.domain.ProviderResult.Outcome.TRANSIENT_REJECTION;
import static me.hanhyur.notify.api.notification.domain.ProviderResult.Outcome.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
class OperatorNotificationActionIntegrationTest {

    private static final String BASE = "/api/v1/notifications";
    private static final String KEY = "Idempotency-Key";
    private static final String CSRF = "X-CSRF-TOKEN";
    private static final String CONFLICT = "STATE_OR_VERSION_CONFLICT";
    private static final String REASON = "공급자 설정을 수정한 뒤 다시 시도합니다.";
    private static final String EVIDENCE = "공급자의 접수 이력과 대조하여 해당 요청의 처리 결과를 확인했습니다.";
    private static final String RECIPIENT = "sample@example.com";
    private static final String CONTENT = "주문 상품이 출고 대기 상태로 변경되었습니다.";
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String API_KEY = UUID.randomUUID().toString();
    private static final UUID OPERATOR = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();
    private static final int RETRY_LIMIT = 16;
    private static final AtomicInteger TEST_HOUR = new AtomicInteger();

    @LocalServerPort private int port;
    @Autowired private JsonMapper json;
    @Autowired private JdbcClient jdbc;
    @Autowired private NotificationOutboxPublicationRepository outbox;
    @Autowired private NotificationDispatchClaimRepository claims;
    @Autowired private NotificationDeliveryResultRepository results;
    @MockitoBean private Clock clock;

    private final HttpClient browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    private Instant now;
    private String csrf;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry settings) {
        String hash = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD);
        Map.of("orders", OPERATOR, "support", SECOND).forEach((name, id) -> {
            String prefix = "notification.auth.operators." + name + ".";
            settings.add(prefix + "operator-id", id::toString);
            settings.add(prefix + "display-name", () -> name);
            settings.add(prefix + "password-hash", () -> hash);
        });
        settings.add("notification.integration.clients.orders", () -> API_KEY);
        settings.add("notification.auth.retry-requests-per-minute", () -> RETRY_LIMIT);
    }

    @BeforeEach
    void advanceToNewWindow() {
        now = Instant.parse("2026-11-23T03:00:00Z").plus(Duration.ofHours(TEST_HOUR.getAndIncrement()));
        when(clock.instant()).thenReturn(now);
    }

    @AfterEach
    void cleanUp() {
        browser.close();
        jdbc.sql("truncate table notifications cascade").update();
    }

    @Test
    void manualRetryCreatesOneNewDispatchAndReplaysTheLatestDetailAfterTheWorkerAdvances() throws Exception {
        login("orders");
        UUID key = UUID.randomUUID();
        UUID id = register(key);
        finish(id, TRANSIENT_REJECTION);
        advance(60);
        var failedDispatch = finish(id, PERMANENT_REJECTION);
        var failed = detail(id);
        assertThat(failed.get("retryCount").asInt()).isOne();
        var previousAttempts = history(id, "attempts");
        int generation = failed.get("generation").asInt() + 1;
        long version = failed.get("version").asLong();
        String body = retryBody(version, REASON);
        advance(10);

        var queued = success(send(command(id, "retries", key, body)), 202, false);
        assertThat(queued.get("notificationId").asString()).isEqualTo(id.toString());
        assertThat(queued.get("status").asString()).isEqualTo("QUEUED");
        assertThat(queued.get("generation").asInt()).isEqualTo(generation);
        assertThat(queued.get("retryCount").asInt()).isZero();
        assertThat(queued.get("version").asLong()).isEqualTo(version + 1);
        for (String field : List.of("acceptedAt", "completedAt", "lastErrorCode", "errorSummary", "nextAttemptAt")) {
            assertThat(queued.get(field).isNull()).as(field).isTrue();
        }
        assertThat(history(id, "attempts")).isEqualTo(previousAttempts);
        assertThat(history(id, "actions").size()).isOne();
        var action = history(id, "actions").get(0);
        assertThat(action.get("operatorId").asString()).isEqualTo(OPERATOR.toString());
        assertThat(action.get("action").asString()).isEqualTo("RETRY");
        assertThat(action.get("reason").asString()).isEqualTo(REASON);
        assertThat(action.get("previousStatus").asString()).isEqualTo("FAILED");
        assertThat(action.get("resultingStatus").asString()).isEqualTo("QUEUED");
        assertThat(action.get("generation").asInt()).isEqualTo(generation);
        assertThat(action.get("createdAt").asString()).isEqualTo(now.toString());

        NotificationOutboxEvent pending = pending(id);
        assertThat(pending.dispatchId()).isNotEqualTo(failedDispatch.dispatchId());
        assertThat(pending.dispatchId().toString()).isEqualTo(queued.get("currentDispatchId").asString());
        assertThat(pending.generation()).isEqualTo(generation);
        assertThat(pending.availableAt()).isEqualTo(now);
        assertThat(claims.tryClaim(failedDispatch, now)).isFalse();
        finish(id, ACCEPTED);
        var replay = success(send(command(id, "retries", key, body)), 202, true);
        assertThat(replay).isEqualTo(detail(id));
        assertThat(replay.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(history(id, "attempts").get(0).get("attemptNo").asInt()).isEqualTo(3);
        assertThat(history(id, "actions").size()).isOne();
        assertThat(outboxCount(id)).isEqualTo(3);
        problem(send(command(id, "retries", key, retryBody(version + 1, REASON))), 409, CONFLICT);
        problem(send(command(id, "retries", key, retryBody(version, REASON + " 변경"))), 409, CONFLICT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "NOT_SENT"})
    void evidenceResolvesAnUnknownResultWithoutChangingTheOriginalAttemptOrSending(String resolution) throws Exception {
        login("orders");
        UUID id = prepared(UNKNOWN);
        var unknown = detail(id);
        var attempts = history(id, "attempts");
        UUID key = UUID.randomUUID();
        String body = resolutionBody(unknown.get("version").asLong(), resolution, EVIDENCE);
        advance(15);
        var resolved = success(send(command(id, "resolutions", key, body)), 200, false);
        String status = resolution.equals("ACCEPTED") ? "ACCEPTED" : "FAILED";
        assertThat(resolved.get("status").asString()).isEqualTo(status);
        assertThat(resolved.get("completedAt").asString()).isEqualTo(now.toString());
        if (resolution.equals("ACCEPTED")) {
            assertThat(resolved.get("acceptedAt").asString()).isEqualTo(now.toString());
            assertThat(resolved.get("allowedActions").size()).isZero();
        } else {
            assertThat(resolved.get("acceptedAt").isNull()).isTrue();
            assertThat(resolved.get("allowedActions").get(0).asString()).isEqualTo("RETRY");
        }
        assertThat(resolved.get("version").asLong()).isEqualTo(unknown.get("version").asLong() + 1);
        for (String field : List.of("currentDispatchId", "generation", "retryCount")) {
            assertThat(resolved.get(field)).as(field).isEqualTo(unknown.get(field));
        }
        assertThat(history(id, "attempts")).isEqualTo(attempts);
        assertThat(outboxCount(id)).isOne();
        var actions = history(id, "actions");
        assertThat(actions.size()).isOne();
        var action = actions.get(0);
        assertThat(action.get("operatorId").asString()).isEqualTo(OPERATOR.toString());
        assertThat(action.get("action").asString()).isEqualTo("RESOLVE_UNKNOWN");
        assertThat(action.get("resolution").asString()).isEqualTo(resolution);
        assertThat(action.get("evidence").asString()).isEqualTo(EVIDENCE);
        assertThat(action.get("reason").isNull()).isTrue();
        assertThat(action.get("previousStatus").asString()).isEqualTo("UNKNOWN");
        assertThat(action.get("resultingStatus").asString()).isEqualTo(status);
        problem(send(command(id, "resolutions", key,
                resolutionBody(unknown.get("version").asLong(), resolution, EVIDENCE + " 보완"))), 409, CONFLICT);
        if (resolution.equals("NOT_SENT")) {
            success(send(command(id, "retries", key, retryBody(resolved.get("version").asLong(), REASON))), 202, false);
        }
        assertThat(success(send(command(id, "resolutions", key, body)), 200, true)).isEqualTo(detail(id));
        assertThat(outboxCount(id)).isEqualTo(resolution.equals("ACCEPTED") ? 1 : 2);
    }

    @Test
    void aKeyBelongsToTheOperatorAndCommandAndCannotBeReusedForAnotherResource() throws Exception {
        login("orders");
        UUID first = prepared(PERMANENT_REJECTION);
        UUID second = prepared(PERMANENT_REJECTION);
        UUID key = UUID.randomUUID();
        String body = retryBody(detail(first).get("version").asLong(), REASON);
        success(send(command(first, "retries", key, body)), 202, false);
        problem(send(command(second, "retries", key, body)), 409, CONFLICT);
        assertThat(history(second, "actions").size()).isZero();
        login("support");
        success(send(command(second, "retries", key, body)), 202, false);
        assertThat(history(second, "actions").get(0).get("operatorId").asString()).isEqualTo(SECOND.toString());
    }

    @Test
    void staleVersionsWrongStatesAndMissingNotificationsDoNotReserveAKeyOrChangeHistory() throws Exception {
        login("orders");
        UUID unknown = prepared(UNKNOWN);
        UUID failed = prepared(PERMANENT_REJECTION);
        UUID key = UUID.randomUUID();
        long version = detail(unknown).get("version").asLong();
        String retry = retryBody(version, REASON);
        String resolve = resolutionBody(version, "ACCEPTED", EVIDENCE);
        problem(send(command(unknown, "retries", key, retry)), 409, CONFLICT);
        problem(send(command(failed, "resolutions", key, resolve)), 409, CONFLICT);
        problem(send(command(failed, "retries", key, retryBody(version - 1, REASON))), 409, CONFLICT);
        problem(send(command(unknown, "resolutions", key, resolutionBody(version - 1, "ACCEPTED", EVIDENCE))), 409, CONFLICT);
        problem(send(command(UUID.randomUUID(), "retries", key, retry)), 404, "NOT_FOUND");
        problem(send(command(UUID.randomUUID(), "resolutions", key, resolve)), 404, "NOT_FOUND");
        for (UUID id : List.of(unknown, failed)) {
            assertThat(outboxCount(id)).isOne();
            assertThat(history(id, "actions").size()).isZero();
            assertThat(history(id, "attempts").size()).isOne();
        }
        success(send(command(unknown, "resolutions", key, resolve)), 200, false);
        success(send(command(failed, "retries", key, retry)), 202, false);
    }

    @Test
    void twoConcurrentRetriesWithOneKeyShareOneEffectAndDifferentKeysRequireTheLatestVersion() throws Exception {
        login("orders");
        for (boolean sameKey : List.of(true, false)) {
            UUID id = prepared(PERMANENT_REJECTION);
            UUID key = UUID.randomUUID();
            String body = retryBody(detail(id).get("version").asLong(), REASON);
            var replies = concurrent(command(id, "retries", key, body),
                    command(id, "retries", sameKey ? key : UUID.randomUUID(), body));
            if (sameKey) {
                assertThat(replies).allMatch(r -> r.statusCode() == 202);
                assertThat(replies).extracting(r -> r.headers().firstValue("Idempotent-Replayed").orElseThrow())
                        .containsExactlyInAnyOrder("true", "false");
            } else {
                assertThat(replies).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(202, 409);
            }
            assertThat(history(id, "actions").size()).isOne();
            assertThat(outboxCount(id)).isEqualTo(2);
            assertThat(detail(id).get("generation").asInt()).isEqualTo(2);
        }
    }

    @Test
    void aFailedAuditInsertRollsBackTheStateOutboxAndReceiptAndTheSameKeyCanBeRetried() throws Exception {
        login("orders");
        UUID id = prepared(PERMANENT_REJECTION);
        UUID key = UUID.randomUUID();
        var before = detail(id);
        var request = command(id, "retries", key, retryBody(before.get("version").asLong(), REASON));
        jdbc.sql("alter table notification_operator_actions add constraint test_unavailable check (action <> 'RETRY') not valid").update();
        try {
            problem(send(request), 503, "PERSISTENCE_UNAVAILABLE");
        } finally {
            jdbc.sql("alter table notification_operator_actions drop constraint test_unavailable").update();
        }
        assertThat(before).isEqualTo(detail(id));
        assertThat(outboxCount(id)).isOne();
        assertThat(history(id, "actions").size()).isZero();
        assertThat(jdbc.sql("select count(*) from operator_action_requests where idempotency_key = :key")
                .param("key", key).query(Integer.class).single()).isZero();
        success(send(request), 202, false);
        assertThat(outboxCount(id)).isEqualTo(2);
    }

    @Test
    void bothActionsRequireAnOperatorSessionAndAFreshCsrfHeader() throws Exception {
        String anonymousToken = token();
        String target = BASE + "/" + UUID.randomUUID();
        for (String path : List.of(target + "/retries", target + "/resolutions")) {
            var unauthorized = post(path, "{}", "application/json", CSRF, anonymousToken, "X-API-Key", API_KEY);
            problem(send(unauthorized), 401, "UNAUTHENTICATED");
        }
        login("orders");
        for (String path : List.of(target + "/retries", target + "/resolutions")) {
            problem(send(post(path, "{}", "application/json")), 403, "FORBIDDEN");
            problem(send(post(path, "{}", "application/json", CSRF, anonymousToken)), 403, "FORBIDDEN");
        }
    }

    @Test
    void badKeysMalformedBodiesAndInvalidAuditTextNeverCreateAnAction() throws Exception {
        login("orders");
        UUID id = prepared(PERMANENT_REJECTION);
        long version = detail(id).get("version").asLong();
        String retry = retryBody(version, REASON);
        UUID key = UUID.randomUUID();
        for (String route : List.of("retries", "resolutions")) {
            String path = BASE + "/" + id + "/" + route;
            String body = route.equals("retries") ? retry : resolutionBody(version, "ACCEPTED", EVIDENCE);
            problem(send(post(path, body, "application/json", CSRF, csrf)), 400, "INVALID_REQUEST");
            problem(send(post(path, body, "application/json", CSRF, csrf, KEY, "1-1-1-1-1")), 400, "INVALID_REQUEST");
            problem(send(post(path, body, "application/json", CSRF, csrf, KEY, key.toString(), KEY, key.toString())), 400, "INVALID_REQUEST");
            problem(send(post(path, body, "text/plain", CSRF, csrf, KEY, key.toString())), 415, "UNSUPPORTED_MEDIA_TYPE");
            problem(send(command(id, route, key, "{")), 400, "INVALID_REQUEST");
        }
        for (String malformed : List.of("{\"expectedVersion\":\"1\",\"reason\":\"잘못된 형식\"}",
                json.writeValueAsString(Map.of("reason", REASON, "expectedVersion", version, "status", "FAILED")))) {
            problem(send(command(id, "retries", key, malformed)), 400, "INVALID_REQUEST");
        }
        problem(send(command(id, "resolutions", key, resolutionBody(version, "MAYBE", EVIDENCE))), 400, "INVALID_REQUEST");
        for (String invalid : List.of("{}", retryBody(-1, REASON), retryBody(version, "🔔".repeat(9)),
                retryBody(version, " "), retryBody(version, "한".repeat(501)))) {
            problem(send(command(id, "retries", key, invalid)), 422, "VALIDATION_FAILED");
        }
        for (String invalid : List.of("{}", resolutionBody(-1, "ACCEPTED", EVIDENCE),
                resolutionBody(version, "ACCEPTED", "🔔".repeat(19)), resolutionBody(version, "NOT_SENT", "한".repeat(1001)))) {
            problem(send(command(id, "resolutions", key, invalid)), 422, "VALIDATION_FAILED");
        }
        advance(60);
        for (String secret : List.of(RECIPIENT, CONTENT, "Bearer " + API_KEY, "01055551234")) {
            var denied = send(command(id, "retries", key, retryBody(version, REASON + " " + secret)));
            problem(denied, 422, "VALIDATION_FAILED");
            assertThat(denied.body()).doesNotContain(secret);
        }
        assertThat(history(id, "actions").size()).isZero();
        assertThat(outboxCount(id)).isOne();
        success(send(command(id, "retries", key, retryBody(version, "🔔".repeat(10)))), 202, false);
    }

    @Test
    void exhaustingTheRetryAllowanceDoesNotBlockResolutionAndResetsAfterOneMinute() throws Exception {
        login("orders");
        UUID id = prepared(PERMANENT_REJECTION);
        UUID unknown = prepared(UNKNOWN);
        var retry = command(id, "retries", UUID.randomUUID(), retryBody(detail(id).get("version").asLong(), REASON));
        for (int i = 0; i < RETRY_LIMIT; i++) {
            success(send(retry), 202, i > 0);
        }
        var limited = send(retry);
        problem(limited, 429, "RATE_LIMITED");
        assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())).isPositive();
        var resolution = resolutionBody(detail(unknown).get("version").asLong(), "NOT_SENT", EVIDENCE);
        success(send(command(unknown, "resolutions", UUID.randomUUID(), resolution)), 200, false);
        advance(60);
        success(send(retry), 202, true);
    }

    private void login(String name) throws Exception {
        var credentials = json.writeValueAsString(Map.of("username", name, "password", PASSWORD));
        var result = send(post("/api/v1/auth/login", credentials, "application/json", CSRF, token()));
        assertThat(result.statusCode()).isEqualTo(200);
        csrf = token();
    }

    private String token() throws Exception {
        return get("/api/v1/auth/csrf").get("token").asString();
    }

    private UUID register(UUID key) throws Exception {
        String body = json.writeValueAsString(Map.of("customerId", "orders", "channel", "EMAIL",
                "recipient", RECIPIENT, "title", "출고 안내", "content", CONTENT));
        var detail = success(send(post(BASE, body, "application/json", CSRF, csrf, KEY, key.toString())), 202, false);
        return UUID.fromString(detail.get("notificationId").asString());
    }

    private UUID prepared(ProviderResult.Outcome outcome) throws Exception {
        UUID id = register(UUID.randomUUID());
        finish(id, outcome);
        return id;
    }

    private NotificationOutboxEvent pending(UUID id) {
        return outbox.findPending(now, 100).stream().filter(event -> event.notificationId().equals(id))
                .findFirst().orElseThrow();
    }

    private NotificationOutboxEvent finish(UUID id, ProviderResult.Outcome outcome) {
        var event = pending(id);
        assertThat(outbox.markPublished(event.eventId(), now)).isTrue();
        assertThat(claims.tryClaim(event, now)).isTrue();
        String reference = outcome == ACCEPTED ? "sandbox-" + UUID.randomUUID() : null;
        results.finish(event, new ProviderResult(outcome, reference), now);
        return event;
    }

    private void advance(int seconds) {
        now = now.plusSeconds(seconds);
        when(clock.instant()).thenReturn(now);
    }

    private JsonNode detail(UUID id) throws Exception {
        return get(BASE + "/" + id);
    }

    private JsonNode history(UUID id, String resource) throws Exception {
        return get(BASE + "/" + id + "/" + resource).get("items");
    }

    private int outboxCount(UUID id) {
        return jdbc.sql("select count(*) from notification_outbox where notification_id = :id")
                .param("id", id).query(Integer.class).single();
    }

    private String retryBody(long version, String reason) {
        return json.writeValueAsString(Map.of("expectedVersion", version, "reason", reason));
    }

    private String resolutionBody(long version, String resolution, String evidence) {
        return json.writeValueAsString(Map.of("expectedVersion", version, "resolution", resolution, "evidence", evidence));
    }

    private HttpRequest command(UUID id, String route, UUID key, String body) {
        return post(BASE + "/" + id + "/" + route, body, "application/json", CSRF, csrf, KEY, key.toString());
    }

    private HttpRequest post(String path, String body, String type, String... headers) {
        var request = request(path).header("Content-Type", type);
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return request.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
    }

    private JsonNode get(String path) throws Exception {
        return read(send(request(path).GET().build()), 200);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return browser.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private List<HttpResponse<String>> concurrent(HttpRequest first, HttpRequest second) throws Exception {
        var a = browser.sendAsync(first, HttpResponse.BodyHandlers.ofString());
        var b = browser.sendAsync(second, HttpResponse.BodyHandlers.ofString());
        return List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
    }

    private JsonNode read(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.uri().getPath()).isEqualTo(status);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        UUID.fromString(response.headers().firstValue("X-Request-ID").orElseThrow());
        return json.readTree(response.body());
    }

    private JsonNode success(HttpResponse<String> response, int status, boolean replayed) {
        var detail = read(response, status);
        assertThat(response.headers().firstValue("Idempotent-Replayed")).contains(Boolean.toString(replayed));
        assertThat(detail.has("recipient")).isFalse();
        assertThat(response.body()).doesNotContain(RECIPIENT);
        return detail;
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        var problem = read(response, status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertThat(problem.get("code").asString()).isEqualTo(code);
        assertThat(problem.get("instance").asString()).isEqualTo(response.uri().getPath());
        assertThat(problem.get("requestId").asString()).isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
    }
}
