CREATE TABLE notification_outbox (
    event_id UUID PRIMARY KEY,
    notification_id UUID NOT NULL,
    dispatch_id UUID NOT NULL,
    generation INTEGER NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT notification_outbox_notification_fk
        FOREIGN KEY (notification_id) REFERENCES notifications (notification_id),
    CONSTRAINT notification_outbox_dispatch_unique UNIQUE (dispatch_id),
    CONSTRAINT notification_outbox_generation_positive CHECK (generation >= 1),
    CONSTRAINT notification_outbox_availability CHECK (available_at >= occurred_at)
);

CREATE INDEX notification_outbox_pending_available_idx
    ON notification_outbox (available_at, event_id)
    WHERE published_at IS NULL;

CREATE INDEX notification_outbox_notification_idx
    ON notification_outbox (notification_id);
