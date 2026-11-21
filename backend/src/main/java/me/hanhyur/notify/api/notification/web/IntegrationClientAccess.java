package me.hanhyur.notify.api.notification.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Component
@EnableConfigurationProperties(IntegrationClientAccess.Settings.class)
final class IntegrationClientAccess implements HandlerInterceptor {

    static final String CLIENT_ID = "notification.integration.authenticatedClientId";

    @ConfigurationProperties("notification.integration")
    record Settings(Map<String, String> clients, Integer requestsPerMinute) {
        @Override
        public String toString() {
            return "IntegrationClientAccess.Settings";
        }
    }

    private final Map<String, Client> clientsByDigest = new HashMap<>();
    private final Integer requestsPerMinute;
    private final Clock clock;

    IntegrationClientAccess(Settings settings, Clock clock) {
        this.clock = clock;
        this.requestsPerMinute = settings.requestsPerMinute();
        if (requestsPerMinute != null && requestsPerMinute < 1) {
            throw new IllegalArgumentException("분당 요청 한도는 양수여야 합니다.");
        }
        if (settings.clients() != null) {
            settings.clients().forEach((id, key) -> {
                if (id.isBlank() || id.length() > 100 || key.isBlank() || key.contains(",")) {
                    throw new IllegalArgumentException("외부 등록 클라이언트 설정이 올바르지 않습니다.");
                }
                if (clientsByDigest.putIfAbsent(digest(key), new Client(id)) != null) {
                    throw new IllegalArgumentException("외부 등록 클라이언트마다 서로 다른 API 키가 필요합니다.");
                }
            });
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws HttpMediaTypeNotSupportedException {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        request.setAttribute(CLIENT_ID, authenticate(request));
        String contentType = request.getContentType();
        try {
            if (contentType != null && MediaType.APPLICATION_JSON.includes(MediaType.parseMediaType(contentType))) {
                return true;
            }
        } catch (InvalidMediaTypeException ignored) {
            throw new HttpMediaTypeNotSupportedException("application/json 요청이 필요합니다.");
        }
        throw new HttpMediaTypeNotSupportedException(null, List.of(MediaType.APPLICATION_JSON), HttpMethod.POST);
    }

    private String authenticate(HttpServletRequest request) {
        String apiKey = singleHeader(request, "X-API-Key");
        Client client = apiKey == null ? null : clientsByDigest.get(digest(apiKey));
        if (client == null) {
            throw IntegrationProblem.UNAUTHENTICATED.exception(request);
        }
        if (requestsPerMinute != null) {
            long retryAfter = client.retryAfterSeconds(clock.instant().getEpochSecond(), requestsPerMinute);
            if (retryAfter > 0) {
                var error = IntegrationProblem.RATE_LIMITED.exception(request);
                error.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
                throw error;
            }
        }
        return client.id;
    }

    static String singleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (!values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        return values.hasMoreElements() || value.isBlank() || value.contains(",") ? null : value;
    }

    private static String digest(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private static final class Client {
        private final String id;
        private long windowEnd;
        private int count;

        private Client(String id) {
            this.id = id;
        }

        synchronized long retryAfterSeconds(long now, int limit) {
            if (now >= windowEnd) {
                windowEnd = now + 60;
                count = 0;
            }
            if (count >= limit) {
                return windowEnd - now;
            }
            count++;
            return 0;
        }
    }
}
