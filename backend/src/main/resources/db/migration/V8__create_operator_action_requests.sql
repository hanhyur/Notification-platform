CREATE TABLE operator_action_requests (
    operator_id UUID NOT NULL,
    command_type VARCHAR(16) NOT NULL CHECK (command_type IN ('RETRY', 'RESOLVE_UNKNOWN')),
    idempotency_key UUID NOT NULL,
    notification_id UUID NOT NULL REFERENCES notifications (notification_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    request_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (operator_id, command_type, idempotency_key)
);
