package me.hanhyur.notify.api.common.database;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SlowQueryMonitorTest {

    private final List<SlowQueryAlert> alerts = new ArrayList<>();
    private final StaticListableBeanFactory beans = new StaticListableBeanFactory();

    SlowQueryMonitorTest() {
        beans.addBean("alerts", (SlowQueryAlertSink) alerts::add);
    }

    @Test
    void onlyAnExecutionStrictlyOverTheThresholdRaisesAnAlert() {
        Duration threshold = Duration.ofMillis(500);
        var monitor = monitor(threshold);
        String requestId = UUID.randomUUID().toString();
        Instant before = Instant.now();
        try (var ignored = MDC.putCloseable("requestId", requestId)) {
            monitor.afterExecution(DatabaseRole.REPLICA, "executeQuery", threshold.minusNanos(1));
            monitor.afterExecution(DatabaseRole.REPLICA, "executeQuery", threshold);
            monitor.afterExecution(DatabaseRole.REPLICA, "executeQuery", threshold.plusNanos(1));
        }
        assertThat(alerts).singleElement().satisfies(alert -> {
            assertThat(alert.databaseRole()).isEqualTo(DatabaseRole.REPLICA);
            assertThat(alert.elapsed()).isEqualTo(threshold.plusNanos(1));
            assertThat(alert.occurredAt()).isBetween(before, Instant.now());
            assertThat(alert.queryId()).contains("SlowQueryMonitorTest#").endsWith("/executeQuery");
            assertThat(alert.requestId()).isEqualTo(requestId);
        });
    }

    @Test
    void preparedExecutionsAndBatchesKeepBindingsOutOfTheAlert() throws SQLException {
        DataSource source = mock();
        Connection physical = mock();
        PreparedStatement statement = mock();
        String sql = "update notifications set content = ?";
        when(source.getConnection()).thenReturn(physical);
        when(physical.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        when(statement.executeBatch()).thenReturn(new int[]{1, 1});
        List<String> values = List.of("sample@example.com", UUID.randomUUID().toString());
        List<String> deliveredRequestIds = new ArrayList<>();
        beans.addBean("requestContext", (SlowQueryAlertSink) alert -> deliveredRequestIds.add(MDC.get("requestId")));
        try (var context = MDC.putCloseable("requestId", values.getFirst());
             var connection = monitor(Duration.ZERO).monitor(source, DatabaseRole.PRIMARY).getConnection();
             var prepared = connection.prepareStatement(sql)) {
            for (String value : values) {
                prepared.setString(1, value);
                assertThat(prepared.executeUpdate()).isEqualTo(1);
                prepared.addBatch();
            }
            assertThat(alerts).hasSize(2);
            assertThat(prepared.executeBatch()).containsExactly(1, 1);
            assertThat(MDC.get("requestId")).isEqualTo(values.getFirst());
        }
        assertThat(alerts).hasSize(3);
        assertThat(deliveredRequestIds).hasSize(3).containsOnlyNulls();
        assertThat(alerts.get(0).queryId()).isEqualTo(alerts.get(1).queryId());
        assertThat(alerts.get(2).queryId()).endsWith("/executeBatch");
        for (SlowQueryAlert alert : alerts) {
            assertThat(alert.databaseRole()).isEqualTo(DatabaseRole.PRIMARY);
            assertThat(alert.requestId()).isNull();
            assertThat(alert.toString()).doesNotContain(sql, values.getFirst(), values.getLast());
        }
    }

    @Test
    void alertFailuresPreserveSuccessfulResultsAndTheOriginalDatabaseError() throws SQLException {
        beans.addBean("unavailableAdapter", (SlowQueryAlertSink) alert -> {
            throw new IllegalStateException("unavailable");
        });
        var monitor = monitor(Duration.ZERO);
        DataSource source = mock();
        Connection physical = mock();
        Statement target = mock();
        when(source.getConnection()).thenReturn(physical);
        when(physical.createStatement()).thenReturn(target);
        String privateSql = "select 'sample@example.com'";
        var failure = new SQLException("query failed", "22000");
        when(target.execute(privateSql)).thenReturn(true).thenThrow(failure);

        try (var connection = monitor.monitor(source, DatabaseRole.PRIMARY).getConnection();
             var statement = connection.createStatement()) {
            assertThat(statement.execute(privateSql)).isTrue();
            SQLException caught = assertThrows(SQLException.class, () -> statement.execute(privateSql));
            assertSame(failure, caught);
        }
        assertThat(alerts).hasSize(2).allSatisfy(alert ->
                assertThat(alert.toString()).doesNotContain(privateSql, "sample@example.com", failure.getMessage()));
    }

    @Test
    void anAdapterUsingTheMonitoredDatabaseDoesNotRecursivelyPublish() {
        var monitor = monitor(Duration.ZERO);
        beans.addBean("adapterWithDatabase", (SlowQueryAlertSink) alert ->
                monitor.afterExecution(DatabaseRole.PRIMARY, "executeUpdate", Duration.ofSeconds(1)));
        monitor.afterExecution(DatabaseRole.REPLICA, "executeQuery", Duration.ofSeconds(1));
        assertThat(alerts).singleElement().satisfies(alert ->
                assertThat(alert.databaseRole()).isEqualTo(DatabaseRole.REPLICA));
    }

    @Test
    void aLazyAdapterInitializationFailureIsIsolated() {
        var unavailable = new DefaultListableBeanFactory();
        var attempted = new AtomicBoolean();
        var definition = new RootBeanDefinition(SlowQueryAlertSink.class);
        definition.setInstanceSupplier(() -> {
            attempted.set(true);
            throw new IllegalStateException("unavailable");
        });
        unavailable.registerBeanDefinition("adapter", definition);
        var monitor = new SlowQueryMonitor(Duration.ZERO, unavailable.getBeanProvider(SlowQueryAlertSink.class));
        monitor.afterExecution(DatabaseRole.PRIMARY, "execute", Duration.ofMillis(1));
        assertThat(attempted).isTrue();
    }

    @Test
    void rejectsNegativeThresholds() {
        assertThrows(IllegalArgumentException.class, () -> monitor(Duration.ofNanos(-1)));
    }

    private SlowQueryMonitor monitor(Duration threshold) {
        return new SlowQueryMonitor(threshold, beans.getBeanProvider(SlowQueryAlertSink.class));
    }
}
