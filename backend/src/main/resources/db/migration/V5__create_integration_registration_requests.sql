CREATE TABLE integration_registration_requests (
    client_id VARCHAR(100) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    notification_id UUID REFERENCES notifications (notification_id) ON DELETE CASCADE,
    PRIMARY KEY (client_id, idempotency_key)
);
