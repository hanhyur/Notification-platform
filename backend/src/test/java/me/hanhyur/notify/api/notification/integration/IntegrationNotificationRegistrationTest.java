package me.hanhyur.notify.api.notification.integration;

import me.hanhyur.notify.api.notification.domain.NotificationDeliveryResultRepository;
import me.hanhyur.notify.api.notification.domain.NotificationDispatchClaimRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublicationRepository;
import me.hanhyur.notify.api.notification.domain.ProviderResult;
import me.hanhyur.notify.api.support.PostgresTestConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class IntegrationNotificationRegistrationTest {

    private static final String PATH = "/integration/v1/notifications";
    private static final String BODY = """
            {"customerId":"order-customer","channel":"EMAIL","recipient":"sample@example.com",
             "title":"주문 안내","content":"주문이 접수되었습니다."}
            """;
    private static final Instant START = Instant.parse("2026-11-21T09:00:00Z");
    private static final AtomicInteger TEST_HOUR = new AtomicInteger();
    private static final String ORDERS = UUID.randomUUID().toString();
    private static final String PAYMENTS = UUID.randomUUID().toString();
    private static final String RATE_LIMIT_CLIENT = UUID.randomUUID().toString();
    private static final int REQUESTS_PER_MINUTE = 4;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort private int port;
    @MockitoBean private Clock clock;
    @Autowired private JsonMapper json;
    @Autowired private JdbcClient jdbc;
    @Autowired private NotificationOutboxPublicationRepository outbox;
    @Autowired private NotificationDispatchClaimRepository claims;
    @Autowired private NotificationDeliveryResultRepository results;
    private Instant now;

    @DynamicPropertySource
    static void configureClients(DynamicPropertyRegistry registry) {
        registry.add("notification.integration.clients.orders", () -> ORDERS);
        registry.add("notification.integration.clients.payments", () -> PAYMENTS);
        registry.add("notification.integration.clients.limited", () -> RATE_LIMIT_CLIENT);
        registry.add("notification.integration.requests-per-minute", () -> REQUESTS_PER_MINUTE);
    }

    @BeforeEach
    void startFreshRateWindow() {
        now = START.plus(Duration.ofHours(TEST_HOUR.getAndIncrement()));
        when(clock.instant()).thenReturn(now);
    }

    @AfterEach
    void clearDatabase() {
        jdbc.sql("truncate table notifications cascade").update();
    }

    @AfterAll
    static void closeHttpClient() {
        HTTP.close();
    }

    @Test
    void commitsOneNotificationAndOutboxAndReplaysTheSameLogicalRequest() throws Exception {
        when(clock.instant()).thenReturn(now.plusNanos(123456789));
        String key = UUID.randomUUID().toString();
        var first = send(request(ORDERS, key, BODY));
        var detail = accepted(first, false);
        assertThat(detail.get("status").asString()).isEqualTo("QUEUED");
        assertThat(detail.get("customerId").asString()).isEqualTo("order-customer");
        assertThat(detail.get("recipientMasked").asString()).contains("*");
        assertThat(first.body()).doesNotContain("sample@example.com", ORDERS);
        assertThat(detail.has("recipient")).isFalse();
        assertThat(detail.get("allowedActions").isArray()).isTrue();
        assertThat(detail.get("allowedActions").size()).isZero();
        UUID.fromString(detail.get("currentDispatchId").asString());
        assertThat(detail.get("scheduledAt").isNull()).isTrue();

        String equivalentBody = """
                {"scheduledAt":null, "recipient":"sample@example.com", "content":"주문이 접수되었습니다.",
                 "customerId":"order-customer", "channel":"EMAIL", "title":"주문 안내"}
                """;
        var replay = send(request(ORDERS, key, equivalentBody));
        assertEquals(detail, accepted(replay, true));
        assertThat(header(replay, "X-Request-ID")).isNotEqualTo(header(first, "X-Request-ID"));
        assertRows(1);
    }

    @Test
    void scopesAKeyToTheAuthenticatedClientAndRejectsDifferentContentWithinThatClient() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = accepted(send(request(ORDERS, key, BODY)), false);
        String different = BODY.replace("주문 안내", "결제 안내");
        assertProblem(send(request(ORDERS, key, different)), 409, "STATE_OR_VERSION_CONFLICT");
        var second = accepted(send(request(PAYMENTS, key, different)), false);
        assertThat(first.get("notificationId")).isNotEqualTo(second.get("notificationId"));
        assertRows(2);
    }

    @Test
    void returnsTheLatestResultEvenAfterTheOriginalScheduledTime() throws Exception {
        String key = UUID.randomUUID().toString();
        Instant scheduled = now.plusSeconds(60);
        String offsetTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(scheduled.atOffset(ZoneOffset.ofHours(9)));
        String submitted = scheduledBody(offsetTime);
        var first = accepted(send(request(ORDERS, key, submitted)), false);
        assertThat(first.get("status").asString()).isEqualTo("SCHEDULED");
        assertThat(first.get("scheduledAt").asString()).isEqualTo(scheduled.toString());
        Instant finished = scheduled.plusSeconds(60);
        var event = outbox.findPending(finished, 1).getFirst();
        assertTrue(claims.tryClaim(event, finished));
        results.finish(event, new ProviderResult(ProviderResult.Outcome.ACCEPTED,
                "sandbox-" + event.dispatchId()), finished);

        when(clock.instant()).thenReturn(finished);
        var replay = accepted(send(request(ORDERS, key, scheduledBody(scheduled.toString()))), true);
        assertEquals(first.get("notificationId"), replay.get("notificationId"));
        assertThat(replay.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(replay.get("version").asLong()).isEqualTo(2);
        assertThat(replay.get("acceptedAt").asString()).isEqualTo(finished.toString());
        assertThat(replay.get("completedAt").asString()).isEqualTo(finished.toString());
        assertRows(1);
    }

    @Test
    void replayIncludesThePendingRetryTimeAndOriginalFailureReason() throws Exception {
        HttpRequest input = request(ORDERS, UUID.randomUUID().toString(), BODY);
        var first = accepted(send(input), false);
        var event = outbox.findPending(now, 1).getFirst();
        assertTrue(outbox.markPublished(event.eventId(), now));
        assertTrue(claims.tryClaim(event, now));
        Instant finished = now.plusSeconds(1);
        results.finish(event, new ProviderResult(ProviderResult.Outcome.TRANSIENT_REJECTION, null), finished);

        when(clock.instant()).thenReturn(finished);
        var replay = accepted(send(input), true);
        assertEquals(first.get("notificationId"), replay.get("notificationId"));
        assertThat(replay.get("currentDispatchId")).isNotEqualTo(first.get("currentDispatchId"));
        assertThat(replay.get("status").asString()).isEqualTo("RETRY_WAIT");
        assertThat(replay.get("retryCount").asInt()).isEqualTo(1);
        assertThat(replay.get("nextAttemptAt").asString()).isEqualTo(finished.plusSeconds(60).toString());
        assertThat(replay.get("lastErrorCode").asString()).isEqualTo("TRANSIENT_CONFIRMED_NOT_ACCEPTED");
        assertThat(replay.get("errorSummary").asString()).isNotBlank();
        assertThat(replay.get("completedAt").isNull()).isTrue();
        assertEquals(2L, jdbc.sql("select count(*) from notification_outbox").query(Long.class).single());
    }

    @Test
    void simultaneousHttpRequestsGetOneNewReceiptAndOneReplay() throws Exception {
        HttpRequest sameRequest = request(ORDERS, UUID.randomUUID().toString(), BODY);
        var first = HTTP.sendAsync(sameRequest, HttpResponse.BodyHandlers.ofString());
        var second = HTTP.sendAsync(sameRequest, HttpResponse.BodyHandlers.ofString());
        var a = first.get(20, TimeUnit.SECONDS);
        var b = second.get(20, TimeUnit.SECONDS);
        assertThat(List.of(a.statusCode(), b.statusCode())).containsExactly(202, 202);
        assertThat(List.of(header(a, "Idempotent-Replayed"), header(b, "Idempotent-Replayed")))
                .containsExactlyInAnyOrder("true", "false");
        assertThat(header(a, "Location")).isEqualTo(header(b, "Location"));
        assertRows(1);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"unregistered"})
    void authenticatesBeforeInspectingMalformedInput(String apiKey) throws Exception {
        assertProblem(send(request(apiKey, null, "{")), 401, "UNAUTHENTICATED");
        assertRows(0);
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {"invalid", "1-1-1-1-1"})
    void requiresAFullIdempotencyUuid(String key) throws Exception {
        assertProblem(send(request(ORDERS, key, BODY)), 400, "INVALID_REQUEST");
        assertRows(0);
    }

    @Test
    void rejectsUnsupportedMediaMalformedJsonAndInvalidFields() throws Exception {
        String key = UUID.randomUUID().toString();
        HttpRequest text = HttpRequest.newBuilder(request(ORDERS, key, BODY), (name, value) -> true)
                .setHeader("Content-Type", "text/plain").build();
        assertProblem(send(text), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertProblem(send(request(ORDERS, key, "{")), 400, "INVALID_REQUEST");
        var invalid = assertProblem(send(request(ORDERS, key, "{}")), 422, "VALIDATION_FAILED");
        assertThat(invalid.get("fieldErrors").has("recipient")).isTrue();
        assertRows(0);
    }

    @Test
    void rejectsUnknownFieldsAndWrongJsonTypesBeforeRegistration() throws Exception {
        String key = UUID.randomUUID().toString();
        for (String body : List.of(BODY.replace("}", ",\"extra\":true}"),
                BODY.replace("}", ",\"scheduledAt\":1800000000}"),
                BODY.replace("\"주문 안내\"", "42"))) {
            assertProblem(send(request(ORDERS, key, body)), 400, "INVALID_REQUEST");
        }
        assertRows(0);
    }

    @Test
    void anInvalidNewScheduleDoesNotReserveTheKey() throws Exception {
        String key = UUID.randomUUID().toString();
        assertProblem(send(request(ORDERS, key, scheduledBody(now.toString()))), 422, "VALIDATION_FAILED");
        assertRows(0);
        accepted(send(request(ORDERS, key, BODY)), false);
        assertRows(1);
    }

    @Test
    void aFailedOutboxInsertRollsBackTheKeyAndAllowsTheSameRequestToSucceed() throws Exception {
        HttpRequest input = request(ORDERS, UUID.randomUUID().toString(), BODY);
        jdbc.sql("alter table notification_outbox add constraint fail_test_insert check (false) not valid").update();
        try {
            assertProblem(send(input), 503, "PERSISTENCE_UNAVAILABLE");
            assertRows(0);
        } finally {
            jdbc.sql("alter table notification_outbox drop constraint fail_test_insert").update();
        }
        accepted(send(input), false);
        assertRows(1);
    }

    @Test
    void throttlesTheClientWithoutConsumingAnotherClientsQuota() throws Exception {
        String key = UUID.randomUUID().toString();
        HttpRequest invalid = request(RATE_LIMIT_CLIENT, key, "{");
        for (int i = 0; i < REQUESTS_PER_MINUTE; i++) {
            assertEquals(400, send(invalid).statusCode());
        }
        var limited = send(invalid);
        assertProblem(limited, 429, "RATE_LIMITED");
        assertThat(Integer.parseInt(header(limited, "Retry-After"))).isPositive();
        assertProblem(send(request(PAYMENTS, key, "{")), 400, "INVALID_REQUEST");
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        assertProblem(send(invalid), 400, "INVALID_REQUEST");
        assertRows(0);
    }

    private static String scheduledBody(String dateTime) {
        return BODY.replace("}", ",\"scheduledAt\":\"" + dateTime + "\"}");
    }

    private HttpRequest request(String apiKey, String idempotencyKey, String body) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null) {
            request.header("X-API-Key", apiKey);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.build();
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode accepted(HttpResponse<String> response, boolean replayed) {
        assertEquals(202, response.statusCode());
        JsonNode detail = json.readTree(response.body());
        UUID id = UUID.fromString(detail.get("notificationId").asString());
        assertThat(header(response, "Location")).isEqualTo("/api/v1/notifications/" + id);
        assertThat(header(response, "Idempotent-Replayed")).isEqualTo(Boolean.toString(replayed));
        assertThat(header(response, "Cache-Control")).isEqualTo("no-store");
        UUID.fromString(header(response, "X-Request-ID"));
        return detail;
    }

    private JsonNode assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode());
        assertThat(header(response, "Content-Type")).startsWith("application/problem+json");
        JsonNode problem = json.readTree(response.body());
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("code").asString()).isEqualTo(code);
        assertThat(problem.get("requestId").asString()).isEqualTo(header(response, "X-Request-ID"));
        assertThat(problem.get("instance").asString()).isEqualTo(PATH);
        assertThat(header(response, "Cache-Control")).isEqualTo("no-store");
        assertThat(response.body()).doesNotContain(ORDERS, PAYMENTS, "sample@example.com");
        return problem;
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElseThrow();
    }

    private void assertRows(long count) {
        assertEquals(count, jdbc.sql("select count(*) from notifications").query(Long.class).single());
        assertEquals(count, jdbc.sql("select count(*) from notification_outbox").query(Long.class).single());
        assertEquals(count, jdbc.sql("select count(*) from integration_registration_requests").query(Long.class).single());
    }
}
