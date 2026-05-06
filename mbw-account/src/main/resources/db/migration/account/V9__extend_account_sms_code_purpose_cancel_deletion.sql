-- cancel-deletion use case (M1.3): extend account.account_sms_code.purpose
-- CHECK constraint to admit the new CANCEL_DELETION discriminator.
--
-- The application-layer AccountSmsCodePurpose enum already lists three values
-- (PHONE_SMS_AUTH, DELETE_ACCOUNT, CANCEL_DELETION); without this migration,
-- inserting a CANCEL_DELETION row hits chk_account_sms_code_purpose at the
-- DB level.
--
-- DROP + ADD on a CHECK constraint is in-place metadata-only on PG; safe on
-- a populated table because all existing rows satisfy the new (broader) set.
ALTER TABLE account.account_sms_code
    DROP CONSTRAINT chk_account_sms_code_purpose;

ALTER TABLE account.account_sms_code
    ADD CONSTRAINT chk_account_sms_code_purpose
        CHECK (purpose IN ('PHONE_SMS_AUTH', 'DELETE_ACCOUNT', 'CANCEL_DELETION'));

COMMENT ON COLUMN account.account_sms_code.purpose
    IS 'Enum: PHONE_SMS_AUTH | DELETE_ACCOUNT | CANCEL_DELETION. Enforced by CHECK constraint + application-layer AccountSmsCodePurpose enum.';
