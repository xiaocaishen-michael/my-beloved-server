-- Account-profile use case: surface a stable identity label on Account.
--
-- Per spec/account/account-profile/{spec.md FR-001 + FR-005 + FR-007,
-- plan.md § V6 migration}: GET /api/v1/accounts/me returns
-- displayName (nullable until onboarding completes); PATCH /me writes it
-- via AccountStateMachine.changeDisplayName. The phoneSmsAuth auto-create
-- path leaves the column NULL (FR-007), preserving the byte-level
-- response invariant from ADR-0016 FR-006.
--
-- Pure expand-only ALTER (nullable add, no default, no unique, no
-- backfill) — single-PR is safe under CLAUDE.md "expand-migrate-contract
-- 跳步条件" (M1.2 stage, no real users, fresh deploys). No unique index
-- because two users may share the same displayName (CL-002 / FR-006).
--
-- VARCHAR(64): codepoint count is bounded to 32 by DisplayName VO; PG
-- VARCHAR(N) measures characters, not bytes, so 64 leaves a comfortable
-- 2x margin for normalisation/migration headroom without burning storage.

ALTER TABLE account.account
    ADD COLUMN display_name VARCHAR(64) NULL;

COMMENT ON COLUMN account.account.display_name
    IS 'User-chosen display label (account-profile FR-001). NULL until onboarding completes; written by PATCH /api/v1/accounts/me. No unique index — collisions are allowed (CL-002).';
