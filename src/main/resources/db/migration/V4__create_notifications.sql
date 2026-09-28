CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message VARCHAR(500) NOT NULL,
    related_entity_type VARCHAR(50) NOT NULL,
    related_entity_id UUID NOT NULL,
    read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT notifications_user_id_fkey FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT notifications_type_check CHECK (type IN ('TRANSFER_SENT', 'TRANSFER_RECEIVED')),
    CONSTRAINT notifications_user_type_entity_unique UNIQUE (user_id, type, related_entity_type, related_entity_id)
);

CREATE INDEX notifications_user_created_idx ON notifications (user_id, created_at DESC, id DESC);
