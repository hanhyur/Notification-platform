package me.hanhyur.notify.api.auth;

import me.hanhyur.notify.api.support.PostgresTestConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"local", "local-https"})
@Import(PostgresTestConfiguration.class)
class LocalHttpsOperatorSessionIntegrationTest {

    private static final String KEYSTORE_PASSWORD = UUID.randomUUID().toString();
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final UUID OPERATOR = UUID.randomUUID();

    @TempDir private static Path workspace;
    @LocalServerPort private int port;
    @Autowired private JsonMapper json;

    @BeforeAll
    static void prepareCertificate() throws Exception {
        Path script = workspace.resolve("prepare-local-cert.sh");
        try (var input = LocalHttpsOperatorSessionIntegrationTest.class
                .getResourceAsStream("/local-https/prepare-local-cert.sh")) {
            Files.copy(Objects.requireNonNull(input), script);
        }
        Path directory = Files.createDirectory(workspace.resolve("certificates"));
        var command = new ProcessBuilder("bash", script.toString(), directory.toString());
        command.environment().put("LOCAL_TLS_PASSWORD", KEYSTORE_PASSWORD);
        var process = command.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("로컬 인증서 생성 완료").isTrue();
            assertThat(process.exitValue()).as("로컬 인증서 생성 명령 종료 코드").isZero();
        } finally {
            process.destroyForcibly();
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("LOCAL_TLS_KEYSTORE", () -> workspace.resolve("certificates/localhost.p12").toUri().toString());
        registry.add("LOCAL_TLS_PASSWORD", () -> KEYSTORE_PASSWORD);
        String hash = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD);
        registry.add("notification.auth.operators.orders.operator-id", OPERATOR::toString);
        registry.add("notification.auth.operators.orders.display-name", () -> "orders");
        registry.add("notification.auth.operators.orders.password-hash", () -> hash);
    }

    @Test
    void defaultTrustDoesNotAcceptTheLocalCertificate() {
        try (var browser = HttpClient.newHttpClient()) {
            assertThatThrownBy(() -> send(browser, request("csrf").GET().build()))
                    .isInstanceOf(SSLHandshakeException.class);
        }
    }

    @Test
    void trustedBrowserUsesSecureSessionCookiesThroughLoginAndLogout() throws Exception {
        try (var browser = HttpClient.newBuilder().sslContext(localTrust()).cookieHandler(new CookieManager()).build()) {
            assertThat(send(browser, request("session").GET().build()).statusCode()).isEqualTo(401);

            String credentials = json.writeValueAsString(Map.of("username", "orders", "password", PASSWORD));
            var login = postWithCsrf(browser, "login", credentials);
            assertThat(login.statusCode()).isEqualTo(200);
            var issued = sessionCookie(login);
            assertThat(issued.getSecure()).as("로그인 세션 쿠키의 Secure 속성").isTrue();
            assertThat(issued.isHttpOnly()).isTrue();
            var session = send(browser, request("session").GET().build());
            assertThat(session.statusCode()).isEqualTo(200);
            assertThat(json.readTree(session.body()).get("operatorId").asString()).isEqualTo(OPERATOR.toString());

            var logout = postWithCsrf(browser, "logout", "");
            assertThat(logout.statusCode()).isEqualTo(204);
            var oldSession = request("session").header("Cookie", "SESSION=" + issued.getValue());
            assertThat(send(browser, oldSession.GET().build()).statusCode()).isEqualTo(401);
            var expired = sessionCookie(logout);
            assertThat(expired.getMaxAge()).isZero();
            assertThat(expired.isHttpOnly()).isTrue();
            assertThat(expired.getSecure()).as("로그아웃 만료 쿠키의 Secure 속성").isTrue();
        }
    }

    private static SSLContext localTrust() throws Exception {
        var trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        try (var pem = Files.newInputStream(workspace.resolve("certificates/localhost.pem"))) {
            trusted.setCertificateEntry("localhost", CertificateFactory.getInstance("X.509").generateCertificate(pem));
        }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trusted);
        var context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }

    private HttpResponse<String> postWithCsrf(HttpClient browser, String path, String body) throws Exception {
        var csrf = send(browser, request("csrf").GET().build());
        assertThat(csrf.statusCode()).isEqualTo(200);
        var token = json.readTree(csrf.body());
        var post = request(path).header(token.get("headerName").asString(), token.get("token").asString())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return send(browser, post.build());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api/v1/auth/" + path))
                .timeout(Duration.ofSeconds(15));
    }

    private static HttpResponse<String> send(HttpClient browser, HttpRequest request) throws Exception {
        return browser.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpCookie sessionCookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream().flatMap(value -> HttpCookie.parse(value).stream())
                .filter(cookie -> cookie.getName().equals("SESSION")).findFirst().orElseThrow();
    }
}
