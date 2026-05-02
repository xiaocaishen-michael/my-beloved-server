-- Refresh-token use case (Phase 1.3): server-side persistence of issued
-- refresh tokens so they can be rotated, revoked, and bulk-invalidated
-- by logout-all (Phase 1.4).
--
-- Per spec/auth/refresh-token/{spec.md FR-003, plan.md § Migration}:
--   token_hash stores the SHA-256 hex digest of the raw refresh token
--   (NOT the plaintext) so a DB leak does not yield usable tokens.
--   Verification flow: client sends raw token → server hashes →
--   findByTokenHash → check expires_at + revoked_at IS NULL.
--
-- Filed as V5 (not V4 as the spec/tasks/analysis docs originally
-- drafted) because V4 was claimed by V4__add_account_last_login_at.sql
-- (P1.1 fix) — Flyway maintains a single global migration history
-- across the shared/ + account/ locations.

CREATE TABLE account.refresh_token (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_hash   VARCHAR(64) NOT NULL,                         -- SHA-256 hex
    account_id   BIGINT NOT NULL,                              -- ref account.id, no FK (kept loose for future module split)
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at   TIMESTAMP WITH TIME ZONE NULL,                -- null = active
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

COMMENT ON TABLE account.refresh_token
    IS 'Server-side records of issued refresh tokens. Rotation-on-each-use (FR-009): each /refresh-token call revokes the row whose hash matched and inserts a new row. logout-all (Phase 1.4) bulk-revokes all of an account_id''s active rows via the partial index below.';
COMMENT ON COLUMN account.refresh_token.token_hash
    IS 'SHA-256(raw refresh token) hex digest, 64 chars lowercase. Storing the hash (not the plaintext) means a DB leak does not yield usable tokens.';

-- Single uniqueness index on the hash — both for collision detection
-- (cosmically rare under SHA-256 + 256-bit raw token) and as the lookup
-- index for findByTokenHash.
CREATE UNIQUE INDEX uk_refresh_token_token_hash
    ON account.refresh_token (token_hash);

-- Partial index covering only the active rows. Phase 1.4 logout-all's
-- bulk-revoke (UPDATE ... WHERE account_id = ? AND revoked_at IS NULL)
-- becomes an index range scan over a small set, even if the full table
-- accumulates millions of revoked rows.
CREATE INDEX idx_refresh_token_account_id_active
    ON account.refresh_token (account_id)
    WHERE revoked_at IS NULL;
