-- Register-by-phone use case: account + credential tables.
--
-- Per spec/account/register-by-phone/{spec.md, plan.md} (FR-005 / CL-001 /
-- DDD aggregate roots Account + Credential sealed hierarchy). Schema is
-- module-isolated (`account`); cross-schema FKs are forbidden by
-- ADR-0001 / meta modular-strategy.md. Within-schema FK from credential
-- to account is allowed and used here.

CREATE TABLE account.account (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    phone       VARCHAR(20) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_account_status CHECK (status IN ('ACTIVE'))
);

COMMENT ON TABLE account.account
    IS 'User accounts. M1.1 lifecycle: (none) -> ACTIVE only (FR-004); FROZEN / ANONYMIZED added in later migrations as those use cases land.';
COMMENT ON COLUMN account.account.phone
    IS 'E.164 phone number including the +86 prefix (FR-001 / FR-005). Single-column UNIQUE index uk_account_phone enforces global uniqueness without country_code split — derived country code planned for M2.';

CREATE UNIQUE INDEX uk_account_phone ON account.account (phone);

CREATE TABLE account.credential (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id      BIGINT NOT NULL REFERENCES account.account (id),
    type            VARCHAR(16) NOT NULL,
    password_hash   VARCHAR(60),
    last_used_at    TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_credential_type CHECK (type IN ('PHONE', 'PASSWORD')),
    CONSTRAINT chk_credential_password_hash_required
        CHECK ((type = 'PASSWORD' AND password_hash IS NOT NULL)
            OR (type = 'PHONE' AND password_hash IS NULL))
);

COMMENT ON TABLE account.credential
    IS 'Credential rows for an account. PHONE rows track lastUsedAt (no hash); PASSWORD rows hold the BCrypt hash. Future credential types (GOOGLE / WECHAT in M1.2+) extend the type enum + chk_credential_type.';
COMMENT ON COLUMN account.credential.password_hash
    IS 'BCrypt hash output (60 chars: $2[abxy]$<cost>$<22-char-salt><31-char-hash>). NULL for PHONE / GOOGLE / WECHAT credentials.';

CREATE INDEX idx_credential_account_id ON account.credential (account_id);

-- Defensive constraint: 1 account x at-most-1 credential per type.
-- spec.md FR-007 maps DataIntegrityViolation on this index to
-- INVALID_CREDENTIALS (uniform error code per anti-enumeration design);
-- the impl distinguishes vs uk_account_phone via SQLState 23505 + index name.
CREATE UNIQUE INDEX uk_credential_account_type
    ON account.credential (account_id, type);
