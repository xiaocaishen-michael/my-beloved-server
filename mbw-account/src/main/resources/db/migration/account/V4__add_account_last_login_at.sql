-- Login-by-phone-sms use case: track last successful login on account.
--
-- Per spec/account/login-by-phone-sms/{spec.md FR-004 + FR-012, plan.md}:
-- on a successful login, AccountRepository.updateLastLoginAt sets this
-- column to now(). Read-side consumers (admin / activity reports / future
-- "last 7d active users" use case) come later — column is added now in
-- expand-only fashion so the login UseCase has a write target.
--
-- Pure expand-only ALTER (no rename / drop / type change) — single-PR is
-- safe under CLAUDE.md "expand-migrate-contract 跳步条件 1" (M1.2 stage,
-- no real users, fresh deploys) plus "1: nothing to back-fill".
--
-- Filed as V4 (not V3 as the spec/tasks/analysis docs originally drafted)
-- because Flyway maintains a single global migration history across
-- shared/ + account/ locations (per app application.yml); shared/V3
-- already exists for Modulith event_publication. The schema collision
-- only surfaces under the full app-level Flyway scan, not the
-- mbw-account-only IT — caught by the first end-to-end IT run.

ALTER TABLE account.account
    ADD COLUMN last_login_at TIMESTAMP WITH TIME ZONE NULL;

COMMENT ON COLUMN account.account.last_login_at
    IS 'Updated by AccountRepository.updateLastLoginAt on successful login (FR-004). NULL until the account first logs in. No index — "last N days active" reporting use cases will add idx_account_last_login_at when introduced (per spec.md analysis.md A7).';
