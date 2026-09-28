CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT users_email_key UNIQUE (email),
    CONSTRAINT users_role_check CHECK (role IN ('CUSTOMER', 'ADMIN'))
);

CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_number VARCHAR(34) NOT NULL,
    user_id UUID NOT NULL,
    balance NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT accounts_account_number_key UNIQUE (account_number),
    CONSTRAINT accounts_user_id_fkey FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT accounts_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT accounts_currency_check CHECK (currency IN ('USD', 'EUR', 'GBP')),
    CONSTRAINT accounts_status_check CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

CREATE TABLE transfers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference VARCHAR(64) NOT NULL,
    source_account_id UUID NOT NULL,
    destination_account_id UUID NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT transfers_reference_key UNIQUE (reference),
    CONSTRAINT transfers_source_account_id_fkey FOREIGN KEY (source_account_id) REFERENCES accounts (id) ON DELETE RESTRICT,
    CONSTRAINT transfers_destination_account_id_fkey FOREIGN KEY (destination_account_id) REFERENCES accounts (id) ON DELETE RESTRICT,
    CONSTRAINT transfers_accounts_differ CHECK (source_account_id <> destination_account_id),
    CONSTRAINT transfers_amount_positive CHECK (amount > 0),
    CONSTRAINT transfers_currency_check CHECK (currency IN ('USD', 'EUR', 'GBP')),
    CONSTRAINT transfers_status_check CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference VARCHAR(64) NOT NULL,
    account_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    type VARCHAR(10) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    balance_after NUMERIC(19, 4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT transactions_reference_key UNIQUE (reference),
    CONSTRAINT transactions_account_id_fkey FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE RESTRICT,
    CONSTRAINT transactions_transfer_id_fkey FOREIGN KEY (transfer_id) REFERENCES transfers (id) ON DELETE RESTRICT,
    CONSTRAINT transactions_type_check CHECK (type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT transactions_balance_after_non_negative CHECK (balance_after >= 0)
);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id UUID NOT NULL,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT audit_logs_user_id_fkey FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE INDEX accounts_user_id_idx ON accounts (user_id);
CREATE INDEX transfers_source_account_id_idx ON transfers (source_account_id);
CREATE INDEX transfers_destination_account_id_idx ON transfers (destination_account_id);
CREATE INDEX transactions_account_id_idx ON transactions (account_id);
CREATE INDEX transactions_transfer_id_idx ON transactions (transfer_id);
CREATE INDEX audit_logs_user_id_idx ON audit_logs (user_id);
CREATE INDEX audit_logs_entity_idx ON audit_logs (entity_type, entity_id);

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER users_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER accounts_set_updated_at
    BEFORE UPDATE ON accounts
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
