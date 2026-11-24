package me.hanhyur.notify.api.common.database;

import java.time.Duration;
import java.time.Instant;

public record SlowQueryAlert(
        String queryId,
        DatabaseRole databaseRole,
        Duration elapsed,
        Instant occurredAt,
        String requestId
) {}
