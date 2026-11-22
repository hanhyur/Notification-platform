package me.hanhyur.notify.api.auth;

import me.hanhyur.notify.api.notification.domain.NotificationDeliveryResultRepository;
import me.hanhyur.notify.api.notification.domain.NotificationDispatchClaimRepository;
import me.hanhyur.notify.api.notification.domain.NotificationOutboxPublicationRepository;
import me.hanhyur.notify.api.notification.domain.ProviderResult;
import me.hanhyur.notify.api.support.PostgresTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.HttpCookie;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class OperatorSessionRegistrationIntegrationTest {

    private static final String AUTH = "/api/v1/auth/";
    private static final String ADMIN = "/api/v1/notifications";
    private static final String EXTERNAL = "/integration/v1/notifications";
    private static final String CSRF = "X-CSRF-TOKEN";
    private static final String KEY = "Idempotency-Key";
    private static final UUID OPERATOR = UUID.randomUUID();
    private static final UUID SECOND_OPERATOR = UUID.randomUUID();
    private static final String PASSWORD = UUID.randomUUID().toString().repeat(3);
    private static final String PASSWORD_HASH =
            Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD);
    private static final String CLIENT_KEY = UUID.randomUUID().toString();
    private static final AtomicInteger TEST_HOUR = new AtomicInteger();
    private static final String BODY = """
            {"customerId":"demo","channel":"EMAIL","recipient":"sample@example.com",
             "title":"주문 안내","content":"주문이 접수되었습니다."}
            """;

    @LocalServerPort private int port;
    @Autowired private JsonMapper json;
    @Autowired private JdbcClient jdbc;
    @Autowired private NotificationOutboxPublicationRepository outbox;
    @Autowired private NotificationDispatchClaimRepository claims;
    @Autowired private NotificationDeliveryResultRepository results;
    @MockitoBean private Clock clock;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient browser = HttpClient.newBuilder().cookieHandler(cookies).build();
    private Instant now;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        operator(registry, "orders", OPERATOR);
        operator(registry, "support", SECOND_OPERATOR);
        registry.add("notification.integration.clients." + OPERATOR, () -> CLIENT_KEY);
        registry.add("notification.auth.login-requests-per-minute", () -> 4);
        registry.add("notification.auth.registration-requests-per-minute", () -> 4);
    }

    private static void operator(DynamicPropertyRegistry registry, String username, UUID id) {
        String prefix = "notification.auth.operators." + username + ".";
        registry.add(prefix + "operator-id", id::toString);
        registry.add(prefix + "display-name", () -> username);
        registry.add(prefix + "password-hash", () -> PASSWORD_HASH);
    }

    @BeforeEach
    void advanceToNewRateWindow() {
        now = Instant.parse("2026-11-21T09:00:00Z").plus(Duration.ofHours(TEST_HOUR.getAndIncrement()));
        when(clock.instant()).thenReturn(now);
    }

    @AfterEach
    void cleanUp() {
        browser.close();
        jdbc.sql("truncate table notifications cascade").update();
    }

    @Test
    void loginMakesTheOperatorAvailableInTheCurrentRequest(@Autowired OperatorAuthController controller) {
        try {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            var operator = controller.login(new OperatorAuthController.LoginRequest("orders", PASSWORD),
                    new MockHttpServletRequest(), new MockHttpServletResponse());

            assertThat(operator.operatorId()).isEqualTo(OPERATOR);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull()
                    .extracting(Authentication::getPrincipal).isEqualTo(operator);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void loginRotatesTheSessionAndCsrfTokenAndLogoutInvalidatesTheSession() throws Exception {
        String anonymousToken = csrfToken();
        String anonymousSession = sessionCookie();
        var login = post(AUTH + "login", credentials("orders", PASSWORD), CSRF, anonymousToken);
        JsonNode identity = session(login, OPERATOR, "orders");
        String cookie = header(login, "Set-Cookie");
        assertThat(cookie).contains("SESSION=", "Path=/", "HttpOnly").containsIgnoringCase("SameSite=Lax");
        String authenticatedSession = sessionCookie();
        assertThat(authenticatedSession).isNotEqualTo(anonymousSession);
        assertThat(session(get(AUTH + "session"), OPERATOR, "orders")).isEqualTo(identity);

        String authenticatedToken = csrfToken();
        assertThat(authenticatedToken).isNotEqualTo(anonymousToken);
        problem(post(AUTH + "logout", "", CSRF, anonymousToken), 403, "FORBIDDEN");
        var logout = post(AUTH + "logout", "", CSRF, authenticatedToken);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.body()).isEmpty();
        assertThat(header(logout, "Set-Cookie")).contains("SESSION=");
        assertThat(cookies.getCookieStore().getCookies()).noneMatch(c -> c.getName().equals("SESSION"));
        var expiredSession = request(AUTH + "session").header("Cookie", "SESSION=" + authenticatedSession);
        problem(send(expiredSession.GET().build()), 401, "UNAUTHENTICATED");
    }

    @Test
    void validatesCredentialsAndThePreLoginCsrfToken() throws Exception {
        String token = csrfToken();
        problem(post(AUTH + "login", credentials("orders", PASSWORD)), 403, "FORBIDDEN");
        problem(post(AUTH + "login", credentials("orders", PASSWORD + "x"), CSRF, token),
                401, "UNAUTHENTICATED");
        problem(post(AUTH + "login", credentials("missing", PASSWORD), CSRF, token), 401, "UNAUTHENTICATED");
        problem(get(AUTH + "session"), 401, "UNAUTHENTICATED");
    }

    @Test
    void rejectsMalformedOrInvalidLoginFields() throws Exception {
        String token = csrfToken();
        problem(post(AUTH + "login", "{", CSRF, token), 400, "INVALID_REQUEST");
        var invalid = problem(post(AUTH + "login", "{}", CSRF, token), 422, "VALIDATION_FAILED");
        assertThat(invalid.get("fieldErrors").propertyNames()).contains("username", "password");
        problem(post(AUTH + "login", credentials("orders", PASSWORD), "Content-Type", "text/plain",
                CSRF, token), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void externalKeysAndSessionCookiesOnlyAuthorizeTheirOwnRoute() throws Exception {
        String key = UUID.randomUUID().toString();
        problem(post(ADMIN, BODY, "X-API-Key", CLIENT_KEY, CSRF, csrfToken(), KEY, key), 401, "UNAUTHENTICATED");
        String token = login("orders", OPERATOR);
        problem(post(ADMIN, BODY, KEY, key), 403, "FORBIDDEN");
        problem(post(EXTERNAL, BODY, CSRF, token, KEY, key), 401, "UNAUTHENTICATED");
        assertRows(0);
    }

    @Test
    void aKeyBelongsToAnOperatorAndIsIndependentFromAnIdenticallyNamedIntegrationClient() throws Exception {
        String key = UUID.randomUUID().toString();
        String token = login("orders", OPERATOR);
        var admin = accepted(post(ADMIN, BODY, CSRF, token, KEY, key), false);
        assertThat(admin.get("allowedActions").size()).isZero();
        assertThat(accepted(post(ADMIN, BODY, CSRF, token, KEY, key), true)).isEqualTo(admin);
        problem(post(ADMIN, BODY.replace("주문 안내", "주문 취소"), CSRF, token, KEY, key),
                409, "STATE_OR_VERSION_CONFLICT");
        assertThat(post(AUTH + "logout", "", CSRF, token).statusCode()).isEqualTo(204);

        String secondToken = login("support", SECOND_OPERATOR);
        var otherOperator = accepted(post(ADMIN, BODY, CSRF, secondToken, KEY, key), false);
        var external = accepted(post(EXTERNAL, BODY, "X-API-Key", CLIENT_KEY, KEY, key), false);
        assertThat(List.of(admin.get("notificationId"), otherOperator.get("notificationId"),
                external.get("notificationId"))).doesNotHaveDuplicates();
        assertRows(3);
    }

    @ParameterizedTest
    @CsvSource({"PERMANENT_REJECTION, FAILED, RETRY", "UNKNOWN, UNKNOWN, RESOLVE_UNKNOWN"})
    void replayExposesOnlyTheActionsAvailableToTheCaller(ProviderResult.Outcome outcome, String status,
            String action) throws Exception {
        String key = UUID.randomUUID().toString();
        String token = login("orders", OPERATOR);
        accepted(post(ADMIN, BODY, CSRF, token, KEY, key), false);
        accepted(post(EXTERNAL, BODY, "X-API-Key", CLIENT_KEY, KEY, key), false);
        var pending = outbox.findPending(now, 2);
        assertThat(pending).hasSize(2);
        for (var event : pending) {
            assertThat(claims.tryClaim(event, now)).isTrue();
            results.finish(event, new ProviderResult(outcome, null), now);
        }

        var admin = accepted(post(ADMIN, BODY, CSRF, token, KEY, key), true);
        assertThat(admin.get("status").asString()).isEqualTo(status);
        assertThat(admin.get("allowedActions").size()).isEqualTo(1);
        assertThat(admin.get("allowedActions").get(0).asString()).isEqualTo(action);
        var external = accepted(post(EXTERNAL, BODY, "X-API-Key", CLIENT_KEY, KEY, key), true);
        assertThat(external.get("status").asString()).isEqualTo(status);
        assertThat(external.get("allowedActions").size()).isZero();
    }

    @Test
    void loginAndRegistrationReportRetryAfterWhenTheirWindowIsFull() throws Exception {
        String anonymous = csrfToken();
        for (int i = 0; i < 4; i++) {
            var denied = post(AUTH + "login", credentials("orders", "wrong"), CSRF, anonymous);
            problem(denied, 401, "UNAUTHENTICATED");
        }
        var throttled = post(AUTH + "login", credentials("orders", PASSWORD), CSRF, anonymous);
        problem(throttled, 429, "RATE_LIMITED");
        assertThat(Integer.parseInt(header(throttled, "Retry-After"))).isPositive();

        when(clock.instant()).thenReturn(now.plusSeconds(61));
        String token = login("orders", OPERATOR);
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 4; i++) {
            accepted(post(ADMIN, BODY, CSRF, token, KEY, key), i > 0);
        }
        var limited = post(ADMIN, BODY, CSRF, token, KEY, key);
        problem(limited, 429, "RATE_LIMITED");
        assertThat(Integer.parseInt(header(limited, "Retry-After"))).isPositive();
        assertRows(1);
    }

    private String login(String username, UUID id) throws Exception {
        session(post(AUTH + "login", credentials(username, PASSWORD), CSRF, csrfToken()), id, username);
        return csrfToken();
    }

    private String credentials(String username, String password) {
        return json.writeValueAsString(Map.of("username", username, "password", password));
    }

    private String csrfToken() throws Exception {
        var response = get(AUTH + "csrf");
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.get("headerName").asString()).isEqualTo(CSRF);
        assertThat(body.get("token").asString()).isNotBlank();
        return body.get("token").asString();
    }

    private String sessionCookie() {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("SESSION"))
                .findFirst().map(HttpCookie::getValue).orElseThrow();
    }

    private JsonNode session(HttpResponse<String> response, UUID id, String name) {
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("operatorId", "displayName", "roles");
        assertThat(body.get("operatorId").asString()).isEqualTo(id.toString());
        assertThat(body.get("displayName").asString()).isEqualTo(name);
        assertThat(body.get("roles").size()).isEqualTo(1);
        assertThat(body.get("roles").get(0).asString()).isEqualTo("OPERATOR");
        return body;
    }

    private JsonNode accepted(HttpResponse<String> response, boolean replayed) {
        assertThat(response.statusCode()).isEqualTo(202);
        var detail = json.readTree(response.body());
        UUID id = UUID.fromString(detail.get("notificationId").asString());
        assertThat(header(response, "Location")).isEqualTo(ADMIN + "/" + id);
        assertThat(header(response, "Idempotent-Replayed")).isEqualTo(Boolean.toString(replayed));
        assertThat(detail.get("recipientMasked").asString()).contains("*");
        assertThat(detail.has("recipient")).isFalse();
        assertThat(response.body()).doesNotContain("sample@example.com");
        return detail;
    }

    private JsonNode problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(header(response, "Content-Type")).startsWith("application/problem+json");
        var body = json.readTree(response.body());
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("instance").asString()).isEqualTo(response.uri().getPath());
        assertThat(body.get("requestId").asString()).isEqualTo(header(response, "X-Request-ID"));
        return body;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(request(path).GET().build());
    }

    private HttpResponse<String> post(String path, String body, String... headers) throws Exception {
        var request = request(path).header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        return send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        var response = browser.send(request, HttpResponse.BodyHandlers.ofString());
        UUID.fromString(header(response, "X-Request-ID"));
        assertThat(header(response, "Cache-Control")).isEqualTo("no-store");
        assertThat(response.body()).doesNotContain(PASSWORD, PASSWORD_HASH, CLIENT_KEY);
        return response;
    }

    private String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElseThrow();
    }

    private void assertRows(long count) {
        assertThat(jdbc.sql("select count(*) from notifications").query(Long.class).single()).isEqualTo(count);
        assertThat(jdbc.sql("select count(*) from notification_outbox").query(Long.class).single()).isEqualTo(count);
    }
}
