-- delete-account use case (M1.3): PG-backed SMS code store keyed by
-- (account_id, purpose). Replaces the Redis-only approach for use cases
-- that require transactional atomicity between code verification and
-- state transitions (e.g. ACTIVE → FROZEN).
--
-- phone-sms-auth codes stay in Redis (RedisVerificationCodeRepository)
-- for its 60-second TTL pattern; this table is for longer-lived, purpose-
-- isolated codes that must survive across JVM restarts and participate in
-- JPA transactions.
CREATE TABLE account.account_sms_code (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  BIGINT NOT NULL,
    code_hash   VARCHAR(64) NOT NULL,
    purpose     VARCHAR(32) NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at     TIMESTAMP WITH TIME ZONE NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_account_sms_code_purpose
        CHECK (purpose IN ('PHONE_SMS_AUTH', 'DELETE_ACCOUNT'))
);

COMMENT ON TABLE account.account_sms_code
    IS 'PG-backed SMS verification codes, purpose-isolated. Redis store handles phone-sms-auth; this table handles delete-account and future transactional use cases.';
COMMENT ON COLUMN account.account_sms_code.code_hash
    IS 'SHA-256 hex digest of the 6-digit plaintext code (64 chars lowercase). Plaintext never stored.';
COMMENT ON COLUMN account.account_sms_code.purpose
    IS 'Enum: PHONE_SMS_AUTH | DELETE_ACCOUNT. Enforced by CHECK constraint + application-layer AccountSmsCodePurpose enum.';

-- Composite partial index: drives findFirstByAccountIdAndPurposeAndUsedAtIsNull...
-- Covers only active (unused) codes, keeping the index small even at scale.
CREATE INDEX idx_account_sms_code_account_purpose_active
    ON account.account_sms_code (account_id, purpose)
    WHERE used_at IS NULL;
