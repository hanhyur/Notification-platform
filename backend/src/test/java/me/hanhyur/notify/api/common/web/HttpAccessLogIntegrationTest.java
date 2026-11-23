package me.hanhyur.notify.api.common.web;

import jakarta.servlet.ServletException;
import me.hanhyur.notify.api.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({HttpAccessLogIntegrationTest.ProbeController.class, PostgresTestConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class HttpAccessLogIntegrationTest {

    private static final String TEMPLATE = "/_test/access/{notificationId}";
    private static final String LOG_PREFIX = RequestIdFilter.class.getName() + " - ";
    @LocalServerPort private int port;

    @Test
    void correlatesBothEventsWithTheResponseAndLogsOnlyApprovedFields(CapturedOutput output) throws Exception {
        String value = UUID.randomUUID().toString();
        String body = "sample@example.com " + UUID.randomUUID();
        var request = HttpRequest.newBuilder(uri("/_test/access/" + value + "?customer=" + value))
                .header("Content-Type", "text/plain")
                .header("Authorization", "Bearer " + value)
                .header("X-API-Key", value)
                .header("X-CSRF-TOKEN", value)
                .header("Cookie", "session=" + value)
                .header(RequestIdFilter.HEADER_NAME, value)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = send(request);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isEqualTo(body);
        String requestId = response.headers().firstValue(RequestIdFilter.HEADER_NAME).orElseThrow();
        assertThat(UUID.fromString(requestId).toString()).isNotEqualTo(value);
        assertAccess(output, requestId, "POST", TEMPLATE, 202);
    }

    @ParameterizedTest
    @CsvSource({"/api/v1/notifications/,401", "/_test/no-route/,404"})
    void masksTheRouteWhenNoControllerHandlesTheRequest(String path, int status, CapturedOutput output)
            throws Exception {
        var response = send(HttpRequest.newBuilder(uri(path + UUID.randomUUID())).GET().build());

        assertThat(response.statusCode()).isEqualTo(status);
        assertAccess(output, response.headers().firstValue(RequestIdFilter.HEADER_NAME).orElseThrow(),
                "GET", "UNMATCHED", status);
    }

    @Test
    void anEscapingExceptionIsRecordedOnceWithTheOriginalRouteAndServerError(CapturedOutput output)
            throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/_test/access/" + UUID.randomUUID())).GET().build());

        assertThat(response.statusCode()).isEqualTo(500);
        assertAccess(output, response.headers().firstValue(RequestIdFilter.HEADER_NAME).orElseThrow(),
                "GET", TEMPLATE, 500);
    }

    @Test
    void preservesTheCommittedStatusAndMasksAnUnrecognizedMethodOnFailure(CapturedOutput output) {
        var request = new MockHttpServletRequest("CUSTOM", "/_test/access/private");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> new RequestIdFilter().doFilter(request, response, (req, res) -> {
            response.setStatus(202);
            response.flushBuffer();
            throw new ServletException("failed after response");
        })).isInstanceOf(ServletException.class);

        assertAccess(output, response.getHeader(RequestIdFilter.HEADER_NAME), "OTHER", "UNMATCHED", 202);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private void assertAccess(CapturedOutput output, String id, String method, String route, int status) {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var lines = output.getOut().lines().filter(line -> line.contains(LOG_PREFIX)).toList();
            assertThat(lines).hasSize(2).allMatch(line -> line.contains("[requestId=" + id + "]"));
            assertThat(lines.get(0)).endsWith(LOG_PREFIX + "http_request_started method=" + method);
            assertThat(lines.get(1)).matches(".*" + Pattern.quote(LOG_PREFIX + "http_request_completed method="
                    + method + " route=" + route + " status=" + status + " durationMs=") + "[0-9]+");
        });
    }

    @TestComponent
    @RestController
    static class ProbeController {
        @PostMapping(TEMPLATE)
        ResponseEntity<String> echo(@RequestBody String body) {
            return ResponseEntity.accepted().body(body);
        }

        @GetMapping(TEMPLATE)
        String fail() {
            throw new IllegalStateException("test exception");
        }
    }
}
