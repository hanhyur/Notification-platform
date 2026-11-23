package me.hanhyur.notify.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

final class OperatorRequestLimit extends OncePerRequestFilter {

    private static final Pattern RETRY_PATH = Pattern.compile("/api/v1/notifications/[^/]+/retries");

    private final OperatorAccounts accounts;
    private final OperatorProblemWriter problems;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();
    private long nextCleanup;

    OperatorRequestLimit(OperatorAccounts accounts, OperatorProblemWriter problems, Clock clock) {
        this.accounts = accounts;
        this.problems = problems;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int limit;
        String key;
        String path = request.getServletPath();
        boolean registration = "/api/v1/notifications".equals(path);
        if ("POST".equals(request.getMethod()) && "/api/v1/auth/login".equals(path)) {
            limit = accounts.loginRequestsPerMinute();
            key = "login/" + request.getRemoteAddr();
        } else if ("POST".equals(request.getMethod()) && (registration || RETRY_PATH.matcher(path).matches())
                && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof OperatorSession operator) {
            limit = registration ? accounts.registrationRequestsPerMinute() : accounts.retryRequestsPerMinute();
            key = (registration ? "register/" : "retry/") + operator.operatorId();
        } else {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = retryAfter(key, limit);
        if (retryAfter > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfter));
            problems.write(request, response, OperatorProblemWriter.Reason.RATE_LIMITED);
            return;
        }
        chain.doFilter(request, response);
    }

    private synchronized long retryAfter(String key, int limit) {
        long now = clock.instant().getEpochSecond();
        if (now >= nextCleanup) {
            windows.values().removeIf(window -> window.end <= now);
            nextCleanup = now + 60;
        }
        var window = windows.get(key);
        if (window == null || now >= window.end) {
            window = new Window(now + 60);
            windows.put(key, window);
        }
        if (window.count >= limit) {
            return window.end - now;
        }
        window.count++;
        return 0;
    }

    private static final class Window {
        private final long end;
        private int count;

        private Window(long end) {
            this.end = end;
        }
    }
}
