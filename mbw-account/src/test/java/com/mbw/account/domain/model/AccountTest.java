package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccountTest {

    private static final PhoneNumber PHONE = new PhoneNumber("+8613800138000");
    private static final Instant CREATED_AT = Instant.parse("2026-04-29T01:00:00Z");

    @Test
    void new_account_should_have_phone_and_createdAt_set_with_no_id_or_status() {
        Account account = new Account(PHONE, CREATED_AT);

        assertThat(account.phone()).isEqualTo(PHONE);
        assertThat(account.createdAt()).isEqualTo(CREATED_AT);
        assertThat(account.updatedAt()).isEqualTo(CREATED_AT);
        assertThat(account.id()).isNull();
        assertThat(account.status()).isNull();
    }

    @Test
    void new_account_should_reject_null_phone() {
        assertThatThrownBy(() -> new Account(null, CREATED_AT)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void new_account_should_reject_null_createdAt() {
        assertThatThrownBy(() -> new Account(PHONE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void reconstitute_should_set_all_fields() {
        AccountId id = new AccountId(42L);
        Instant updatedAt = CREATED_AT.plusSeconds(60);

        Account account = Account.reconstitute(id, PHONE, AccountStatus.ACTIVE, CREATED_AT, updatedAt);

        assertThat(account.id()).isEqualTo(id);
        assertThat(account.phone()).isEqualTo(PHONE);
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.createdAt()).isEqualTo(CREATED_AT);
        assertThat(account.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void reconstitute_should_reject_null_components() {
        AccountId id = new AccountId(42L);
        assertThatThrownBy(() -> Account.reconstitute(null, PHONE, AccountStatus.ACTIVE, CREATED_AT, CREATED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Account.reconstitute(id, PHONE, null, CREATED_AT, CREATED_AT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void assignId_should_set_id_when_unassigned() {
        Account account = new Account(PHONE, CREATED_AT);
        AccountId id = new AccountId(7L);

        account.assignId(id);

        assertThat(account.id()).isEqualTo(id);
    }

    @Test
    void assignId_should_reject_reassignment() {
        Account account = new Account(PHONE, CREATED_AT);
        account.assignId(new AccountId(7L));

        assertThatThrownBy(() -> account.assignId(new AccountId(8L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already assigned");
    }

    @Test
    void assignId_should_reject_null() {
        Account account = new Account(PHONE, CREATED_AT);
        assertThatThrownBy(() -> account.assignId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void account_should_have_no_phone_setter() throws Exception {
        // Compile-time check via reflection: no public setter for phone exists.
        boolean hasPhoneSetter = java.util.Arrays.stream(Account.class.getMethods())
                .anyMatch(m ->
                        m.getName().equals("setPhone") || m.getName().equals("phone") && m.getParameterCount() > 0);
        assertThat(hasPhoneSetter).isFalse();
    }

    @Test
    void new_account_should_have_null_lastLoginAt() {
        Account account = new Account(PHONE, CREATED_AT);
        assertThat(account.lastLoginAt()).isNull();
    }

    @Test
    void reconstitute_should_carry_lastLoginAt() {
        AccountId id = new AccountId(42L);
        Instant lastLogin = CREATED_AT.plusSeconds(120);
        Account account = Account.reconstitute(id, PHONE, AccountStatus.ACTIVE, CREATED_AT, lastLogin, lastLogin);

        assertThat(account.lastLoginAt()).isEqualTo(lastLogin);
    }

    @Test
    void markLoggedIn_should_update_lastLoginAt_and_updatedAt_when_ACTIVE() {
        Account account = Account.reconstitute(
                new AccountId(1L), PHONE, AccountStatus.ACTIVE, CREATED_AT, CREATED_AT, /* lastLoginAt= */ null);
        Instant loggedInAt = CREATED_AT.plusSeconds(60);

        AccountStateMachine.markLoggedIn(account, loggedInAt);

        assertThat(account.lastLoginAt()).isEqualTo(loggedInAt);
        assertThat(account.updatedAt()).isEqualTo(loggedInAt);
    }

    @Test
    void markLoggedIn_should_throw_when_status_is_null() {
        Account account = new Account(PHONE, CREATED_AT); // status=null, never activated
        assertThatThrownBy(() -> AccountStateMachine.markLoggedIn(account, CREATED_AT.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markLoggedIn_should_throw_when_status_is_FROZEN() {
        Account account = Account.reconstitute(
                new AccountId(1L), PHONE, AccountStatus.FROZEN, CREATED_AT, CREATED_AT, /* lastLoginAt= */ null);
        assertThatThrownBy(() -> AccountStateMachine.markLoggedIn(account, CREATED_AT.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void new_account_should_have_null_displayName() {
        Account account = new Account(PHONE, CREATED_AT);

        assertThat(account.displayName()).isNull();
    }

    @Test
    void reconstitute_6_arg_overload_should_default_displayName_to_null() {
        AccountId id = new AccountId(42L);

        Account account = Account.reconstitute(id, PHONE, AccountStatus.ACTIVE, CREATED_AT, CREATED_AT, CREATED_AT);

        assertThat(account.displayName()).isNull();
    }

    @Test
    void reconstitute_7_arg_overload_should_carry_displayName() {
        AccountId id = new AccountId(42L);
        DisplayName name = new DisplayName("Alice");

        Account account =
                Account.reconstitute(id, PHONE, AccountStatus.ACTIVE, CREATED_AT, CREATED_AT, CREATED_AT, name);

        assertThat(account.displayName()).isEqualTo(name);
    }

    @Test
    void reconstitute_7_arg_overload_should_accept_null_displayName() {
        AccountId id = new AccountId(42L);

        Account account = Account.reconstitute(
                id, PHONE, AccountStatus.ACTIVE, CREATED_AT, CREATED_AT, CREATED_AT, /* displayName= */ null);

        assertThat(account.displayName()).isNull();
    }

    // --- T2: markFrozen (delete-account M1.3) ---

    @Test
    void new_account_should_have_null_freezeUntil() {
        Account account = new Account(PHONE, CREATED_AT);
        assertThat(account.freezeUntil()).isNull();
    }

    @Test
    void reconstitute_8_arg_overload_should_carry_freezeUntil() {
        AccountId id = new AccountId(42L);
        Instant freezeUntil = CREATED_AT.plusSeconds(15L * 24 * 3600);

        Account account =
                Account.reconstitute(id, PHONE, AccountStatus.FROZEN, CREATED_AT, CREATED_AT, null, null, freezeUntil);

        assertThat(account.freezeUntil()).isEqualTo(freezeUntil);
        assertThat(account.status()).isEqualTo(AccountStatus.FROZEN);
    }

    @Test
    void should_transition_to_FROZEN_with_freezeUntil_when_markFrozen_called_on_ACTIVE() {
        Account account = Account.reconstitute(new AccountId(1L), PHONE, AccountStatus.ACTIVE, CREATED_AT, CREATED_AT);
        Instant freezeUntil = CREATED_AT.plusSeconds(15L * 24 * 3600);
        Instant now = CREATED_AT.plusSeconds(60);

        AccountStateMachine.markFrozen(account, freezeUntil, now);

        assertThat(account.status()).isEqualTo(AccountStatus.FROZEN);
        assertThat(account.freezeUntil()).isEqualTo(freezeUntil);
        assertThat(account.updatedAt()).isEqualTo(now);
    }

    @Test
    void should_throw_IllegalStateException_when_markFrozen_called_on_FROZEN() {
        Account account = Account.reconstitute(new AccountId(1L), PHONE, AccountStatus.FROZEN, CREATED_AT, CREATED_AT);

        assertThatThrownBy(() -> AccountStateMachine.markFrozen(
                        account, CREATED_AT.plusSeconds(1_000), CREATED_AT.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    void should_throw_IllegalStateException_when_markFrozen_called_on_ANONYMIZED() {
        Account account =
                Account.reconstitute(new AccountId(1L), PHONE, AccountStatus.ANONYMIZED, CREATED_AT, CREATED_AT);

        assertThatThrownBy(() -> AccountStateMachine.markFrozen(
                        account, CREATED_AT.plusSeconds(1_000), CREATED_AT.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- T0: markActiveFromFrozen (cancel-deletion M1.3) ---

    @Test
    void should_transition_FROZEN_to_ACTIVE_clearing_freezeUntil_when_grace_not_expired() {
        Instant freezeUntil = CREATED_AT.plusSeconds(15L * 24 * 3600);
        Account account = Account.reconstitute(
                new AccountId(1L), PHONE, AccountStatus.FROZEN, CREATED_AT, CREATED_AT, null, null, freezeUntil);
        Instant now = CREATED_AT.plusSeconds(60);

        AccountStateMachine.markActiveFromFrozen(account, now);

        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.freezeUntil()).isNull();
        assertThat(account.updatedAt()).isEqualTo(now);
    }

    @Test
    void should_throw_when_markActiveFromFrozen_called_on_ACTIVE() {
        Account account = Account.reconstitute(new AccountId(1L), PHONE, AccountStatus.ACTIVE, CREATED_AT, CREATED_AT);

        assertThatThrownBy(() -> AccountStateMachine.markActiveFromFrozen(account, CREATED_AT.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCOUNT_NOT_FROZEN_IN_GRACE");
    }

    @Test
    void should_throw_when_markActiveFromFrozen_called_on_ANONYMIZED() {
        Account account =
                Account.reconstitute(new AccountId(1L), PHONE, AccountStatus.ANONYMIZED, CREATED_AT, CREATED_AT);

        assertThatThrownBy(() -> AccountStateMachine.markActiveFromFrozen(account, CREATED_AT.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCOUNT_NOT_FROZEN_IN_GRACE");
    }

    @Test
    void should_throw_when_markActiveFromFrozen_called_on_FROZEN_with_grace_expired() {
        Instant freezeUntil = CREATED_AT.plusSeconds(60);
        Account account = Account.reconstitute(
                new AccountId(1L), PHONE, AccountStatus.FROZEN, CREATED_AT, CREATED_AT, null, null, freezeUntil);
        Instant now = freezeUntil.plusSeconds(1); // grace expired

        assertThatThrownBy(() -> AccountStateMachine.markActiveFromFrozen(account, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCOUNT_NOT_FROZEN_IN_GRACE");
    }

    @Test
    void should_throw_when_markActiveFromFrozen_called_on_FROZEN_with_freezeUntil_null() {
        // FROZEN status with null freezeUntil: invariant violation, treated same as not-in-grace
        Account account = Account.reconstitute(
                new AccountId(1L),
                PHONE,
                AccountStatus.FROZEN,
                CREATED_AT,
                CREATED_AT,
                null,
                null, /* freezeUntil */
                null);

        assertThatThrownBy(() -> AccountStateMachine.markActiveFromFrozen(account, CREATED_AT.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCOUNT_NOT_FROZEN_IN_GRACE");
    }

    // --- T2: markAnonymized + previousPhoneHash (anonymize-frozen-accounts M1.3) ---

    private static final String PHONE_HASH = "ec61f3c620a98bdead8c1f1f0ae747abd1b62a0c2dba4fd4bc22cf0d1d8653e5";
    private static final String PLACEHOLDER_DISPLAY_NAME = "已注销用户";

    @Test
    void new_account_should_have_null_previousPhoneHash() {
        Account account = new Account(PHONE, CREATED_AT);

        assertThat(account.previousPhoneHash()).isNull();
    }

    @Test
    void reconstitute_8_arg_overload_should_default_previousPhoneHash_to_null() {
        Instant freezeUntil = CREATED_AT.plusSeconds(15L * 24 * 3600);
        Account account = Account.reconstitute(
                new AccountId(1L), PHONE, AccountStatus.FROZEN, CREATED_AT, CREATED_AT, null, null, freezeUntil);

        assertThat(account.previousPhoneHash()).isNull();
    }

    @Test
    void reconstitute_9_arg_overload_should_carry_previousPhoneHash() {
        Account account = Account.reconstitute(
                new AccountId(1L),
                /* phone= */ null,
                AccountStatus.ANONYMIZED,
                CREATED_AT,
                CREATED_AT,
                null,
                new DisplayName(PLACEHOLDER_DISPLAY_NAME),
                /* freezeUntil= */ null,
                PHONE_HASH);

        assertThat(account.previousPhoneHash()).isEqualTo(PHONE_HASH);
        assertThat(account.phone()).isNull();
        assertThat(account.status()).isEqualTo(AccountStatus.ANONYMIZED);
    }

    @Test
    void reconstitute_9_arg_should_reject_null_phone_for_non_ANONYMIZED_status() {
        // Phone-null is only legitimate after anonymization. Non-ANONYMIZED
        // rows with null phone would corrupt phone-sms-auth lookups.
        assertThatThrownBy(() -> Account.reconstitute(
                        new AccountId(1L),
                        /* phone= */ null,
                        AccountStatus.ACTIVE,
                        CREATED_AT,
                        CREATED_AT,
                        null,
                        null,
                        null,
                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("phone");
    }

    @Test
    void should_anonymize_FROZEN_account_clearing_phone_setting_hash_when_grace_expired() {
        Instant freezeUntil = CREATED_AT.plusSeconds(15L * 24 * 3600);
        Account account = Account.reconstitute(
                new AccountId(1L),
                PHONE,
                AccountStatus.FROZEN,
                CREATED_AT,
                CREATED_AT,
                CREATED_AT,
                new DisplayName("Alice"),
                freezeUntil);
        Instant graceExpiredAt = freezeUntil.plusSeconds(1);

        account.markAnonymized(graceExpiredAt, PLACEHOLDER_DISPLAY_NAME, PHONE_HASH);

        assertThat(account.status()).isEqualTo(AccountStatus.ANONYMIZED);
        assertThat(account.phone()).isNull();
        assertThat(account.previousPhoneHash()).isEqualTo(PHONE_HASH);
        assertThat(account.displayName()).isEqualTo(new DisplayName(PLACEHOLDER_DISPLAY_NAME));
        assertThat(account.freezeUntil()).isNull();
        assertThat(account.updatedAt()).isEqualTo(graceExpiredAt);
    }

    @Test
    void should_throw_when_markAnonymized_called_on_ACTIVE() {
        Account account = Account.reconstitute(new AccountId(1L), PHONE, AccountStatus.ACTIVE, CREATED_AT, CREATED_AT);

        assertThatThrownBy(
                        () -> account.markAnonymized(CREATED_AT.plusSeconds(60), PLACEHOLDER_DISPLAY_NAME, PHONE_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FROZEN");
    }

    @Test
    void should_throw_when_markAnonymized_called_on_ANONYMIZED_idempotency_guard() {
        Account account = Account.reconstitute(
                new AccountId(1L),
                /* phone= */ null,
                AccountStatus.ANONYMIZED,
                CREATED_AT,
                CREATED_AT,
                null,
                new DisplayName(PLACEHOLDER_DISPLAY_NAME),
                /* freezeUntil= */ null,
                PHONE_HASH);

        assertThatThrownBy(
                        () -> account.markAnonymized(CREATED_AT.plusSeconds(60), PLACEHOLDER_DISPLAY_NAME, PHONE_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FROZEN");
    }

    @Test
    void should_throw_when_markAnonymized_called_on_FROZEN_with_grace_not_expired() {
        Instant freezeUntil = CREATED_AT.plusSeconds(15L * 24 * 3600);
        Account account = Account.reconstitute(
                new AccountId(1L), PHONE, AccountStatus.FROZEN, CREATED_AT, CREATED_AT, null, null, freezeUntil);
        Instant beforeGraceExpiry = CREATED_AT.plusSeconds(60);

        assertThatThrownBy(() -> account.markAnonymized(beforeGraceExpiry, PLACEHOLDER_DISPLAY_NAME, PHONE_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("freeze_until");
    }

    @Test
    void should_throw_when_markAnonymized_called_on_FROZEN_with_freezeUntil_null() {
        Account account = Account.reconstitute(
                new AccountId(1L),
                PHONE,
                AccountStatus.FROZEN,
                CREATED_AT,
                CREATED_AT,
                null,
                null,
                /* freezeUntil= */ null);

        assertThatThrownBy(
                        () -> account.markAnonymized(CREATED_AT.plusSeconds(60), PLACEHOLDER_DISPLAY_NAME, PHONE_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("freeze_until");
    }

    @Test
    void should_throw_when_markAnonymized_phoneHash_null() {
        Instant freezeUntil = CREATED_AT.plusSeconds(15L * 24 * 3600);
        Account account = Account.reconstitute(
                new AccountId(1L), PHONE, AccountStatus.FROZEN, CREATED_AT, CREATED_AT, null, null, freezeUntil);

        assertThatThrownBy(() -> account.markAnonymized(
                        freezeUntil.plusSeconds(1), PLACEHOLDER_DISPLAY_NAME, /* phoneHash= */ null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("phoneHash");
    }

    @Test
    void should_throw_when_markAnonymized_displayNamePlaceholder_null() {
        Instant freezeUntil = CREATED_AT.plusSeconds(15L * 24 * 3600);
        Account account = Account.reconstitute(
                new AccountId(1L), PHONE, AccountStatus.FROZEN, CREATED_AT, CREATED_AT, null, null, freezeUntil);

        assertThatThrownBy(() -> account.markAnonymized(
                        freezeUntil.plusSeconds(1), /* displayNamePlaceholder= */ null, PHONE_HASH))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("displayNamePlaceholder");
    }
}
