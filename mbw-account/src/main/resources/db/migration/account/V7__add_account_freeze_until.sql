-- delete-account use case (M1.3): freeze_until tracks 15-day grace period
-- between FROZEN transition and ANONYMIZED scheduled task.
-- NULL when account is ACTIVE; non-null only while FROZEN.
-- Scheduler-driven anonymize (separate use case) scans WHERE
-- status='FROZEN' AND freeze_until < now().
ALTER TABLE account.account
    ADD COLUMN freeze_until TIMESTAMP WITH TIME ZONE NULL;

COMMENT ON COLUMN account.account.freeze_until
    IS 'When the FROZEN account becomes eligible for anonymization. NULL while ACTIVE/ANONYMIZED.';

-- Partial index for the scheduler scan (separate use case will use it):
CREATE INDEX idx_account_freeze_until_active
    ON account.account (freeze_until)
    WHERE status = 'FROZEN' AND freeze_until IS NOT NULL;

-- Extend the status CHECK constraint to allow FROZEN + ANONYMIZED.
-- V2 restricted to 'ACTIVE' only per M1.1 scope; delete-account (M1.3)
-- introduces the first ACTIVE → FROZEN transition.
ALTER TABLE account.account DROP CONSTRAINT chk_account_status;
ALTER TABLE account.account
    ADD CONSTRAINT chk_account_status
        CHECK (status IN ('ACTIVE', 'FROZEN', 'ANONYMIZED'));
