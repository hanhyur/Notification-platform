package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationProvider;
import me.hanhyur.notify.api.notification.domain.ProviderResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SandboxHttpNotificationProvider implements NotificationProvider, AutoCloseable {

    private static final int MAX_RESPONSE_BYTES = 4096;
    private static final ProviderResult UNKNOWN = new ProviderResult(ProviderResult.Outcome.UNKNOWN, null);

    private final JsonMapper json;
    private final ObjectReader replies;
    private final URI endpoint;
    private final long responseTimeoutNanos;
    private final HttpClient client;

    public SandboxHttpNotificationProvider(JsonMapper json, URI endpoint, Duration connectTimeout, Duration responseTimeout) {
        this.json = Objects.requireNonNull(json);
        this.replies = json.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
        this.endpoint = Objects.requireNonNull(endpoint);
        if (endpoint.getHost() == null || !("http".equalsIgnoreCase(endpoint.getScheme())
                || "https".equalsIgnoreCase(endpoint.getScheme())) || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("모의 공급자의 발송 HTTP 주소를 지정하세요.");
        }
        if (!responseTimeout.isPositive()) {
            throw new IllegalArgumentException("모의 공급자 응답 대기 시간은 양수여야 합니다.");
        }
        this.responseTimeoutNanos = responseTimeout.toNanos();
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public ProviderResult send(Notification notification) {
        byte[] body = json.writeValueAsBytes(Map.of(
                "dispatchId", notification.currentDispatchId(),
                "channel", notification.channel(),
                "recipient", notification.recipient(),
                "title", notification.title(),
                "content", notification.content()));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var pending = client.sendAsync(request, info -> limitedReply());
        try {
            var response = pending.get(responseTimeoutNanos, TimeUnit.NANOSECONDS);
            String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip();
            if (!"application/json".equalsIgnoreCase(type)) {
                return UNKNOWN;
            }
            JsonNode reply = replies.readValue(response.body());
            if (reply == null || !reply.isObject()) {
                return UNKNOWN;
            }
            return classify(response.statusCode(), reply, notification.currentDispatchId());
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return UNKNOWN;
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            return UNKNOWN;
        } catch (ExecutionException | JacksonException failure) {
            return UNKNOWN;
        }
    }

    private ProviderResult classify(int statusCode, JsonNode reply, UUID dispatchId) {
        String status = reply.path("status").asString("");
        String reference = reply.path("reference").asString("");
        String expectedReference = "sandbox-" + dispatchId;

        if (statusCode == 202 && "ACCEPTED".equals(status)
                && expectedReference.equals(reference) && reply.size() == 2) {
            return new ProviderResult(ProviderResult.Outcome.ACCEPTED, reference);
        }

        if (statusCode == 422
                && "PERMANENT_REJECTION".equals(status) && reply.size() == 1) {
            return new ProviderResult(ProviderResult.Outcome.PERMANENT_REJECTION, null);
        }

        if (statusCode == 503
                && "TRANSIENT_REJECTION".equals(status) && reply.size() == 1) {
            return new ProviderResult(ProviderResult.Outcome.TRANSIENT_REJECTION, null);
        }

        return UNKNOWN;
    }

    private static HttpResponse.BodySubscriber<byte[]> limitedReply() {
        var bytes = new ByteArrayOutputStream();
        var subscriber = HttpResponse.BodySubscribers.ofByteArrayConsumer(chunk -> chunk.ifPresent(data -> {
            if (data.length > MAX_RESPONSE_BYTES - bytes.size()) {
                throw new IllegalArgumentException("모의 공급자 응답이 허용 크기를 초과했습니다.");
            }
            bytes.writeBytes(data);
        }));
        return HttpResponse.BodySubscribers.mapping(subscriber, ignored -> bytes.toByteArray());
    }

    @Override
    public void close() {
        client.shutdownNow();
    }
}
