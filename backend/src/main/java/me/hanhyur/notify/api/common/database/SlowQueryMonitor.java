package me.hanhyur.notify.api.common.database;

import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

@Component
public final class SlowQueryMonitor {

    private static final Logger log = LoggerFactory.getLogger(SlowQueryMonitor.class);
    private static final StackWalker STACK = StackWalker.getInstance();
    private static final Pattern REQUEST_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final Duration threshold;
    private final ObjectProvider<SlowQueryAlertSink> sinks;
    private final ThreadLocal<Boolean> publishing = new ThreadLocal<>();

    public SlowQueryMonitor(@Value("${notification.database.slow-query-threshold:500ms}") Duration threshold,
                            ObjectProvider<SlowQueryAlertSink> sinks) {
        if (threshold.isNegative()) {
            throw new IllegalArgumentException("느린 쿼리 기준 시간은 0 이상이어야 합니다.");
        }
        this.threshold = threshold;
        this.sinks = sinks;
    }

    public DataSource monitor(DataSource source, DatabaseRole role) {
        return ProxyDataSourceBuilder.create(role.name(), source)
                .beforeQuery((execution, queries) -> execution.addCustomValue("started", System.nanoTime()))
                .afterQuery((execution, queries) -> {
                    long started = execution.getCustomValue("started", Long.class);
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
                    afterExecution(role, execution.getMethod().getName(), elapsed);
                })
                .build();
    }

    void afterExecution(DatabaseRole role, String jdbcMethod, Duration elapsed) {
        if (elapsed.compareTo(threshold) > 0) {
            String queryId = queryId(jdbcMethod);

            SlowQueryAlert slowQueryAlert = new SlowQueryAlert(queryId, role, elapsed, Instant.now(), requestId());

            publish(slowQueryAlert);
        }
    }

    private static String queryId(String jdbcMethod) {
        return STACK.walk(frames -> frames
                .filter(frame -> frame.getClassName().startsWith("me.hanhyur.notify."))
                .filter(frame -> !frame.getClassName().equals(SlowQueryMonitor.class.getName()))
                .filter(frame -> frame.getLineNumber() > 0)
                .findFirst()
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber()
                        + "/" + jdbcMethod)
                .orElse("jdbc/" + jdbcMethod));
    }

    private static String requestId() {
        String id = MDC.get("requestId");

        return id != null && REQUEST_ID.matcher(id).matches() ? id : null;
    }

    private void publish(SlowQueryAlert alert) {
        if (Boolean.TRUE.equals(publishing.get())) {
            return;
        }
        publishing.set(true);
        String invalidRequestId = alert.requestId() == null ? MDC.get("requestId") : null;
        if (invalidRequestId != null) {
            MDC.remove("requestId");
        }
        try {
            log.warn("slow_query queryId={} databaseRole={} elapsedMs={}",
                    alert.queryId(), alert.databaseRole(), alert.elapsed().toMillis());
            for (SlowQueryAlertSink sink : sinks) {
                try {
                    sink.accept(alert);
                } catch (RuntimeException failure) {
                    log.warn("slow_query_alert_delivery_failed queryId={}", alert.queryId());
                }
            }
        } catch (BeansException unavailable) {
            log.warn("slow_query_alert_sink_unavailable queryId={}", alert.queryId());
        } finally {
            if (invalidRequestId != null) {
                MDC.put("requestId", invalidRequestId);
            }
            publishing.remove();
        }
    }
}
