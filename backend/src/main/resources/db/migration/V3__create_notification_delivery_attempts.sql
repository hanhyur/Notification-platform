CREATE TABLE notification_delivery_attempts (
    attempt_id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notifications (notification_id),
    dispatch_id UUID NOT NULL UNIQUE,
    attempt_no INTEGER NOT NULL CHECK (attempt_no >= 1),
    status TEXT NOT NULL CHECK (status IN ('STARTED', 'ACCEPTED', 'REJECTED', 'UNKNOWN')),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (notification_id, attempt_no)
);
