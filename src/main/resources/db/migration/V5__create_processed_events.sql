CREATE TABLE processed_events (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT processed_events_event_consumer_unique UNIQUE (event_id, consumer_name)
);
