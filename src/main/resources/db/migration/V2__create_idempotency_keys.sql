CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    transfer_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT idempotency_keys_user_id_fkey FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT idempotency_keys_transfer_id_fkey FOREIGN KEY (transfer_id) REFERENCES transfers (id) ON DELETE RESTRICT,
    CONSTRAINT idempotency_keys_user_key_unique UNIQUE (user_id, idempotency_key),
    CONSTRAINT idempotency_keys_status_check CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT idempotency_keys_completion_check CHECK (
        (status = 'IN_PROGRESS' AND transfer_id IS NULL)
        OR (status = 'COMPLETED' AND transfer_id IS NOT NULL)
    )
);

CREATE INDEX transfers_created_at_id_idx ON transfers (created_at DESC, id);
