CREATE TABLE notifications (
    notification_id UUID PRIMARY KEY,
    current_dispatch_id UUID NOT NULL,
    customer_id VARCHAR(100) NOT NULL,
    channel TEXT NOT NULL,
    recipient VARCHAR(320) NOT NULL,
    title VARCHAR(100) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    status TEXT NOT NULL,

    scheduled_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    generation INTEGER NOT NULL DEFAULT 1,
    retry_count INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT notification_supported_channel CHECK (
        channel IN ('EMAIL', 'SMS', 'KAKAOTALK')
    ),

    CONSTRAINT notification_status CHECK (
        status IN ('SCHEDULED', 'QUEUED', 'SENDING', 'ACCEPTED', 'RETRY_WAIT', 'FAILED', 'UNKNOWN')
    ),

    CONSTRAINT notification_generation_positive CHECK (
        generation >= 1
    ),

    CONSTRAINT notification_retry_count CHECK (
        retry_count BETWEEN 0 AND 3
    ),

    CONSTRAINT notification_version CHECK (
        version >= 0
    ),

    CONSTRAINT notifications_non_empty_payload CHECK (
        char_length(customer_id) >= 1
        AND char_length(recipient) >= 1
        AND char_length(title) >= 1
        AND char_length(content) >= 1
    )
);
