-- Realname verification use case (spec/account/realname-verification, M1.X).
--
-- Per plan.md § 数据模型变更（Flyway V11）: introduces a single new table
-- account.realname_profile to hold the encrypted PII (real name + ID card)
-- + lifecycle status (UNVERIFIED / PENDING / VERIFIED / FAILED) for each
-- account that goes through identity verification.
--
-- Design choices captured in plan.md and adjacent ADRs:
--   * No cross-schema FK to account.account(id) — consistent with
--     login_audit / account_agreement / refresh_token; referential
--     integrity is enforced at the application layer (D-001).
--   * id_card_hash is partial-unique: NULL is allowed (UNVERIFIED rows
--     keep all PII columns NULL); a non-NULL hash is globally unique to
--     enforce FR-013 "one ID card belongs to at most one account at a
--     time" (SC-003 concurrency test asserts this).
--   * updated_at is maintained by the application layer (JPA @PreUpdate)
--     in line with account.account and every other table in this schema —
--     no DB trigger. See plan.md § 数据模型变更 amend.

CREATE TABLE account.realname_profile (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id      BIGINT NOT NULL UNIQUE,
    status          VARCHAR(16) NOT NULL,
    real_name_enc   BYTEA,
    id_card_no_enc  BYTEA,
    id_card_hash    CHAR(64),
    provider_biz_id VARCHAR(64),
    verified_at     TIMESTAMP WITH TIME ZONE,
    failed_reason   VARCHAR(32),
    failed_at       TIMESTAMP WITH TIME ZONE,
    retry_count_24h INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_realname_status
        CHECK (status IN ('UNVERIFIED', 'PENDING', 'VERIFIED', 'FAILED'))
);

COMMENT ON TABLE account.realname_profile
    IS 'Realname verification state per account (FR-001..FR-015). Encrypted PII (AES-GCM via CipherService) + sha256(idCard||pepper) hash for cross-account uniqueness. Lifecycle: UNVERIFIED -> PENDING -> {VERIFIED, FAILED}; FAILED -> PENDING (retry). FR-015 forbids physical delete; account anonymization clears PII columns + resets to UNVERIFIED (per anonymize-frozen-accounts amend).';
COMMENT ON COLUMN account.realname_profile.id_card_hash
    IS 'sha256(id_card_no || MBW_REALNAME_HASH_PEPPER), 64 hex chars. NULL in UNVERIFIED state; partial-unique on non-NULL values enforces one-card-one-account globally.';
COMMENT ON COLUMN account.realname_profile.provider_biz_id
    IS 'Server-generated UUID v4 (D-003). Sent to Aliyun realname API as the idempotency key; clients then poll GET /verifications/{providerBizId}.';
COMMENT ON COLUMN account.realname_profile.retry_count_24h
    IS 'Rolling 24h failed-attempt counter (FR-009 / SC-005). Reset on successful VERIFIED transition. USER_CANCELED outcomes do NOT increment (per ConfirmRealnameVerificationUseCase).';

-- Partial unique on id_card_hash: only enforce uniqueness when hash is set.
-- UNVERIFIED rows (hash IS NULL) coexist freely.
CREATE UNIQUE INDEX uk_realname_profile_id_card_hash
    ON account.realname_profile (id_card_hash)
    WHERE id_card_hash IS NOT NULL;

-- Lookup index for ConfirmRealnameVerificationUseCase
-- (GET /verifications/{providerBizId}).
CREATE INDEX idx_realname_profile_provider_biz_id
    ON account.realname_profile (provider_biz_id);
