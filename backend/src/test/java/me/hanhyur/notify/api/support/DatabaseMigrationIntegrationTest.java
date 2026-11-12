package me.hanhyur.notify.api.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class DatabaseMigrationIntegrationTest {

    private static final Instant NOW =
            Instant.parse("2026-11-08T09:00:00.123456Z");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Flyway flyway;

    @Autowired
    private PostgreSQLContainer postgres;

    @AfterEach
    void clearNotifications() {
        jdbc.sql("DELETE FROM notification_outbox").update();
        jdbc.sql("DELETE FROM notifications").update();
    }

    @Test
    void connectsToIsolatedPostgres() throws Exception {
        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            assertEquals("PostgreSQL", metadata.getDatabaseProductName());
            assertEquals(18, metadata.getDatabaseMajorVersion());
            assertEquals(postgres.getJdbcUrl(), metadata.getURL());
        }
    }

    @Test
    void appliesBothSchemaMigrations() {
        assertNotNull(flyway.info().current());
        assertEquals("2", flyway.info().current().getVersion().getVersion());
        assertEquals(2, flyway.info().applied().length);
        assertTrue(jdbc.sql(
                "SELECT to_regclass('public.notifications') IS NOT NULL"
        ).query(Boolean.class).single());
        assertTrue(jdbc.sql(
                "SELECT to_regclass('public.notification_outbox') IS NOT NULL"
        ).query(Boolean.class).single());
        flyway.validate();
    }

    @Test
    void doesNotReapplyMigrationOrLoseRows() {
        Fixture fixture = insert("EMAIL", "QUEUED", null);

        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(1L, jdbc.sql(
                "SELECT count(*) FROM notifications WHERE notification_id = :id"
        ).param("id", fixture.id()).query(Long.class).single());
    }

    @Test
    void storesUuidAndUtcTimestamps() {
        Instant scheduledAt = NOW.plusSeconds(60);
        Fixture fixture = insert("EMAIL", "SCHEDULED", scheduledAt);
        Stored row = find(fixture.id());

        assertAll(
                () -> assertEquals(fixture.id(), row.id()),
                () -> assertEquals(fixture.dispatchId(), row.dispatchId()),
                () -> assertEquals(NOW, row.createdAt()),
                () -> assertEquals(NOW, row.updatedAt()),
                () -> assertEquals(scheduledAt, row.scheduledAt())
        );
    }

    @Test
    void keepsImmediateScheduleNull() {
        Fixture fixture = insert("EMAIL", "QUEUED", null);
        assertNull(find(fixture.id()).scheduledAt());
    }

    @Test
    void initializesProcessingDefaults() {
        Fixture fixture = insert("EMAIL", "QUEUED", null);
        Stored row = find(fixture.id());

        assertAll(
                () -> assertEquals(1, row.generation()),
                () -> assertEquals(0, row.retryCount()),
                () -> assertEquals(0L, row.version())
        );
    }

    @Test
    void rejectsDuplicateNotificationId() {
        Fixture fixture = insert("EMAIL", "QUEUED", null);

        assertThrows(DuplicateKeyException.class, () -> jdbc.sql("""
                INSERT INTO notifications
                SELECT * FROM notifications WHERE notification_id = :id
                """).param("id", fixture.id()).update());
    }

    @ParameterizedTest
    @CsvSource({"INVALID,QUEUED", "EMAIL,INVALID"})
    void rejectsUnsupportedChannelOrStatus(String channel, String status) {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insert(channel, status, null)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"customer_id", "recipient", "title", "content"})
    void rejectsMissingRequiredPayload(String column) {
        Fixture fixture = insert("EMAIL", "QUEUED", null);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> update(fixture.id(), column, null, Types.VARCHAR)
        );
    }

    @ParameterizedTest
    @CsvSource({"generation,0", "retry_count,-1", "retry_count,4", "version,-1"})
    void rejectsInvalidProcessingCounters(String column, int value) {
        Fixture fixture = insert("EMAIL", "QUEUED", null);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> update(fixture.id(), column, value, Types.INTEGER)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"customer_id", "recipient", "title", "content"})
    void rejectsEmptyPayload(String column) {
        Fixture fixture = insert("EMAIL", "QUEUED", null);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> update(fixture.id(), column, "", Types.VARCHAR)
        );
    }

    @ParameterizedTest
    @CsvSource({"customer_id,101", "content,2001"})
    void rejectsPayloadBeyondColumnLimit(String column, int length) {
        Fixture fixture = insert("EMAIL", "QUEUED", null);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> update(fixture.id(), column, "a".repeat(length), Types.VARCHAR)
        );
    }

    @Test
    void countsUnicodeCodePointsAtColumnLimit() {
        Fixture fixture = insert("EMAIL", "QUEUED", null);
        String customerId = "\uD83D\uDE00".repeat(100);

        update(fixture.id(), "customer_id", customerId, Types.VARCHAR);

        assertEquals(customerId, jdbc.sql("""
                SELECT customer_id FROM notifications WHERE notification_id = :id
                """).param("id", fixture.id()).query(String.class).single());
    }

    private Fixture insert(String channel, String status, Instant scheduledAt) {
        Fixture fixture = new Fixture(UUID.randomUUID(), UUID.randomUUID());
        jdbc.sql("""
                INSERT INTO notifications (
                    notification_id, current_dispatch_id, customer_id, channel,
                    recipient, title, content, status, scheduled_at,
                    created_at, updated_at
                ) VALUES (
                    :id, :dispatchId, :customerId, :channel,
                    :recipient, :title, :content, :status, :scheduledAt,
                    :createdAt, :updatedAt
                )
                """)
                .param("id", fixture.id())
                .param("dispatchId", fixture.dispatchId())
                .param("customerId", "customer-1001")
                .param("channel", channel)
                .param("recipient", "sample@example.com")
                .param("title", "예약 안내")
                .param("content", "예약이 접수되었습니다.")
                .param("status", status)
                .param("scheduledAt", scheduledAt == null ? null
                        : scheduledAt.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdAt", NOW.atOffset(ZoneOffset.ofHours(9)))
                .param("updatedAt", NOW.atOffset(ZoneOffset.UTC))
                .update();
        return fixture;
    }

    private Stored find(UUID id) {
        return jdbc.sql("""
                SELECT notification_id, current_dispatch_id, created_at, updated_at,
                       scheduled_at, generation, retry_count, version
                FROM notifications WHERE notification_id = :id
                """).param("id", id)
                .query((rs, rowNum) -> new Stored(
                        rs.getObject("notification_id", UUID.class),
                        rs.getObject("current_dispatch_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                        instant(rs.getObject("scheduled_at", OffsetDateTime.class)),
                        rs.getInt("generation"),
                        rs.getInt("retry_count"),
                        rs.getLong("version")
                )).single();
    }

    private void update(UUID id, String column, Object value, int sqlType) {
        String sql = switch (column) {
            case "customer_id" -> "UPDATE notifications SET customer_id = :value WHERE notification_id = :id";
            case "recipient" -> "UPDATE notifications SET recipient = :value WHERE notification_id = :id";
            case "title" -> "UPDATE notifications SET title = :value WHERE notification_id = :id";
            case "content" -> "UPDATE notifications SET content = :value WHERE notification_id = :id";
            case "generation" -> "UPDATE notifications SET generation = :value WHERE notification_id = :id";
            case "retry_count" -> "UPDATE notifications SET retry_count = :value WHERE notification_id = :id";
            case "version" -> "UPDATE notifications SET version = :value WHERE notification_id = :id";
            default -> throw new IllegalArgumentException(column);
        };

        jdbc.sql(sql).param("value", value, sqlType).param("id", id).update();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record Fixture(UUID id, UUID dispatchId) {
    }

    private record Stored(
            UUID id, UUID dispatchId,
            Instant createdAt, Instant updatedAt, Instant scheduledAt,
            int generation, int retryCount, long version
    ) {
    }
}
