ALTER TABLE integration_registration_requests
    ADD COLUMN owner_type VARCHAR(16) NOT NULL DEFAULT 'INTEGRATION'
        CHECK (owner_type IN ('INTEGRATION', 'OPERATOR')),
    DROP CONSTRAINT integration_registration_requests_pkey,
    ADD PRIMARY KEY (owner_type, client_id, idempotency_key);
