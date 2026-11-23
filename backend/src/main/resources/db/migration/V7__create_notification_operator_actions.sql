CREATE TABLE notification_operator_actions (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notifications (notification_id),
    operator_id UUID NOT NULL,
    action TEXT NOT NULL,
    reason VARCHAR(500),
    evidence VARCHAR(1000),
    resolution TEXT,
    previous_status TEXT NOT NULL,
    resulting_status TEXT NOT NULL,
    generation INTEGER NOT NULL CHECK (generation >= 1),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT operator_action_transition CHECK (
        (action = 'RETRY' AND previous_status = 'FAILED' AND resulting_status = 'QUEUED'
            AND reason IS NOT NULL AND char_length(reason) >= 10 AND evidence IS NULL AND resolution IS NULL)
        OR
        (action = 'RESOLVE_UNKNOWN' AND previous_status = 'UNKNOWN'
            AND reason IS NULL AND evidence IS NOT NULL AND char_length(evidence) >= 20
            AND resolution IS NOT NULL AND (
                (resolution = 'ACCEPTED' AND resulting_status = 'ACCEPTED')
                OR (resolution = 'NOT_SENT' AND resulting_status = 'FAILED')
            ))
    )
);

CREATE INDEX notification_operator_actions_by_notification
    ON notification_operator_actions (notification_id, created_at DESC, id DESC);

CREATE INDEX notifications_by_creation
    ON notifications (created_at DESC, notification_id DESC);
