package me.hanhyur.notify.api.notification.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationFactory;
import me.hanhyur.notify.api.notification.domain.NotificationSchedulePolicy;
import me.hanhyur.notify.api.notification.domain.ProviderResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(12)
class SandboxHttpNotificationProviderTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final Notification notification = new NotificationFactory(Clock.systemUTC(), new NotificationSchedulePolicy())
            .create("customer-1", Channel.EMAIL, "sample@example.com", "모의 제목", "모의 본문", null);
    private final Duration timeout = Duration.ofSeconds(3);
    private HttpServer server;
    private URI endpoint;
    private SandboxHttpNotificationProvider provider;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/deliveries");
        provider = new SandboxHttpNotificationProvider(json, endpoint, timeout, timeout);
    }

    @AfterEach
    void stopServer() {
        provider.close();
        server.stop(0);
    }

    @Test
    void postsOnlyTheDeliveryFieldsAndRecognizesTheReferenceForThisDispatch() {
        var method = new AtomicReference<String>();
        var contentType = new AtomicReference<String>();
        var body = new AtomicReference<byte[]>();
        var calls = new AtomicInteger();
        String reference = "sandbox-" + notification.currentDispatchId();
        server.createContext("/deliveries", exchange -> {
            calls.incrementAndGet();
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(exchange.getRequestBody().readAllBytes());
            reply(exchange, 202, "application/json; charset=UTF-8",
                    json.writeValueAsString(Map.of("status", "ACCEPTED", "reference", reference)));
        });

        assertEquals(new ProviderResult(ProviderResult.Outcome.ACCEPTED, reference), provider.send(notification));

        assertEquals(1, calls.get());
        assertEquals("POST", method.get());
        assertEquals("application/json", contentType.get());
        assertEquals(Map.of("dispatchId", notification.currentDispatchId().toString(), "channel", "EMAIL",
                "recipient", notification.recipient(), "title", notification.title(), "content", notification.content()),
                json.readValue(body.get(), Map.class));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "422 | {\"status\":\"PERMANENT_REJECTION\"} | PERMANENT_REJECTION",
            "503 | {\"status\":\"TRANSIENT_REJECTION\"} | TRANSIENT_REJECTION",
            "422 | {\"status\":\"TRANSIENT_REJECTION\"} | UNKNOWN",
            "500 | {\"status\":\"TRANSIENT_REJECTION\"} | UNKNOWN",
            "429 | {} | UNKNOWN",
            "503 | {} | UNKNOWN",
            "202 | {\"status\":\"ACCEPTED\"} | UNKNOWN",
            "202 | {\"status\":\"ACCEPTED\",\"reference\":\"sample@example.com\"} | UNKNOWN",
            "202 | {\"status\":\"ACCEPTED\",\"reference\":\"sandbox-00000000-0000-0000-0000-000000000001\"} | UNKNOWN",
            "422 | {\"status\":\"ACCEPTED\",\"status\":\"PERMANENT_REJECTION\"} | UNKNOWN",
            "503 | {\"status\":\"TRANSIENT_REJECTION\"} {} | UNKNOWN",
            "202 | invalid-json | UNKNOWN"
    })
    void requiresMatchingEvidenceForAnOutcome(int code, String body, ProviderResult.Outcome outcome) {
        server.createContext("/deliveries", exchange -> reply(exchange, code, "application/json", body));

        assertEquals(new ProviderResult(outcome, null), provider.send(notification));
    }

    @Test
    void doesNotForwardDeliveryFieldsToARedirect() {
        var followed = new AtomicInteger();
        server.createContext("/deliveries", exchange -> {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            reply(exchange, 307, "application/json", "{}");
        });
        server.createContext("/elsewhere", exchange -> {
            followed.incrementAndGet();
            reply(exchange, 503, "application/json", "{\"status\":\"TRANSIENT_REJECTION\"}");
        });

        assertEquals(ProviderResult.Outcome.UNKNOWN, provider.send(notification).outcome());
        assertEquals(0, followed.get());
    }

    @Test
    void rejectsNonJsonAndOversizedResponses() {
        var calls = new AtomicInteger();
        String body = "{\"status\":\"PERMANENT_REJECTION\"}";
        server.createContext("/deliveries", exchange -> {
            if (calls.getAndIncrement() == 0) {
                reply(exchange, 422, "text/plain", body);
            } else {
                reply(exchange, 422, "application/json", " ".repeat(5000) + body);
            }
        });

        assertEquals(ProviderResult.Outcome.UNKNOWN, provider.send(notification).outcome());
        assertEquals(ProviderResult.Outcome.UNKNOWN, provider.send(notification).outcome());
        assertEquals(2, calls.get());
    }

    @Test
    void stopsWaitingForAnIncompleteBodyWithoutResending() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        server.createContext("/deliveries", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                started.countDown();
                release.await(8, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var shortWait = new SandboxHttpNotificationProvider(json, endpoint, timeout, Duration.ofSeconds(1))) {
            var result = executor.submit(() -> shortWait.send(notification));
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS));
                assertEquals(ProviderResult.Outcome.UNKNOWN, result.get(4, TimeUnit.SECONDS).outcome());
                assertEquals(1, calls.get());
            } finally {
                release.countDown();
            }
        }
    }

    private static void reply(HttpExchange exchange, int status, String type, String content) throws IOException {
        try (exchange) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
