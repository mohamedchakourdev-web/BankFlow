CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    CONSTRAINT outbox_events_status_check CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT outbox_events_attempts_non_negative CHECK (attempts >= 0)
);

CREATE INDEX outbox_events_pending_idx ON outbox_events (created_at, id) WHERE status = 'PENDING';
