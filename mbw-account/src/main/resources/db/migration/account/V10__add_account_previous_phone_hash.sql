-- anonymize-frozen-accounts use case (M1.3): preserve hashed phone on
-- anonymization for fraud detection + identity verification by support staff
-- (per spec CL-003 (b) hash decision; SHA-256 hex, 64 chars).
-- ACTIVE/FROZEN accounts: NULL.
-- ANONYMIZED accounts: SHA-256 hex of pre-anonymize phone value.
ALTER TABLE account.account
    ADD COLUMN previous_phone_hash VARCHAR(64) NULL;

COMMENT ON COLUMN account.account.previous_phone_hash
    IS 'SHA-256 hex of pre-anonymize phone for fraud detection. NULL while ACTIVE/FROZEN.';

-- Index for "has this phone been anonymized before?" lookup
-- (fraud check during phone-sms-auth auto-create path).
CREATE INDEX idx_account_previous_phone_hash
    ON account.account (previous_phone_hash)
    WHERE previous_phone_hash IS NOT NULL;

-- Drop NOT NULL on phone — anonymization clears the column to satisfy
-- spec.md FR-003 (PII removal). The unique index uk_account_phone is
-- plain (not partial); PG semantics already permit multiple NULL values
-- under a plain UNIQUE INDEX, so this single change is enough to allow
-- anonymized rows without rewriting the index.
ALTER TABLE account.account
    ALTER COLUMN phone DROP NOT NULL;
