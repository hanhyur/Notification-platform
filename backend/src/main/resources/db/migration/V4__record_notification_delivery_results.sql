ALTER TABLE notifications
    ADD COLUMN accepted_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_error_code VARCHAR(100),
    ADD COLUMN error_summary VARCHAR(500);

ALTER TABLE notification_delivery_attempts
    ADD COLUMN finished_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN failure_category VARCHAR(100) CHECK (failure_category IN
        ('TRANSIENT_CONFIRMED_NOT_ACCEPTED', 'PERMANENT_CONFIRMED_NOT_ACCEPTED', 'AMBIGUOUS')),
    ADD COLUMN provider_reference VARCHAR(200),
    ADD COLUMN error_code VARCHAR(100),
    ADD COLUMN error_summary VARCHAR(500),
    ADD CONSTRAINT attempt_finish_after_start CHECK (finished_at >= started_at);

WITH unfinished AS (
    UPDATE notification_delivery_attempts a
    SET status = 'UNKNOWN',
        finished_at = GREATEST(CURRENT_TIMESTAMP, a.started_at, n.updated_at),
        failure_category = 'AMBIGUOUS',
        error_code = 'AMBIGUOUS',
        error_summary = '공급자가 요청을 접수했는지 확인할 수 없습니다.'
    FROM notifications n
    WHERE n.notification_id = a.notification_id
      AND n.current_dispatch_id = a.dispatch_id
      AND n.status = 'SENDING'
      AND a.status = 'STARTED'
    RETURNING a.notification_id, a.finished_at, a.error_code, a.error_summary
)
UPDATE notifications n
SET status = 'UNKNOWN',
    updated_at = unfinished.finished_at,
    version = n.version + 1,
    last_error_code = unfinished.error_code,
    error_summary = unfinished.error_summary
FROM unfinished
WHERE n.notification_id = unfinished.notification_id;
