package me.hanhyur.notify.api.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    public static final String HEADER_NAME = "X-Request-ID";
    public static final String MDC_KEY = "requestId";
    public static final String ATTRIBUTE_NAME =
            RequestIdFilter.class.getName() + ".requestId";

    @Override
    public void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        String method = logMethod(request.getMethod());

        request.setAttribute(ATTRIBUTE_NAME, requestId);
        response.setHeader(HEADER_NAME, requestId);

        MDC.put(MDC_KEY, requestId);

        try {
            log.info("http_request_started method={}", method);
            boolean returned = false;
            try {
                filterChain.doFilter(request, response);
                returned = true;
            } finally {
                int status = returned || response.isCommitted()
                        ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                boolean controllerMatched = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)
                        instanceof HandlerMethod;
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                log.info("http_request_completed method={} route={} status={} durationMs={}", method,
                        routeTemplate(controllerMatched, pattern), status,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            }
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String logMethod(String method) {
        return switch (method) {
            case "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT" -> method;
            case null, default -> "OTHER";
        };
    }

    private static String routeTemplate(boolean controllerMatched, Object pattern) {
        if (controllerMatched && pattern instanceof String template && !template.isBlank()) {
            return template;
        }

        return "UNMATCHED";
    }
}
