package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccountStateMachineTest {

    private static final PhoneNumber PHONE = new PhoneNumber("+8613800138000");
    private static final Instant CREATED_AT = Instant.parse("2026-04-29T01:00:00Z");
    private static final Instant ACTIVATION_AT = Instant.parse("2026-04-29T01:00:30Z");

    @Test
    void activate_should_transition_unstatused_account_to_ACTIVE() {
        Account account = new Account(PHONE, CREATED_AT);

        Account result = AccountStateMachine.activate(account, ACTIVATION_AT);

        assertThat(result).isSameAs(account);
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void activate_should_set_updatedAt_to_activation_instant() {
        Account account = new Account(PHONE, CREATED_AT);

        AccountStateMachine.activate(account, ACTIVATION_AT);

        assertThat(account.updatedAt()).isEqualTo(ACTIVATION_AT);
        assertThat(account.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void activate_should_reject_already_ACTIVE_account() {
        Account account = Account.reconstitute(new AccountId(1L), PHONE, AccountStatus.ACTIVE, CREATED_AT, CREATED_AT);

        assertThatThrownBy(() -> AccountStateMachine.activate(account, ACTIVATION_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    void activate_should_reject_FROZEN_account() {
        Account account = Account.reconstitute(new AccountId(1L), PHONE, AccountStatus.FROZEN, CREATED_AT, CREATED_AT);

        assertThatThrownBy(() -> AccountStateMachine.activate(account, ACTIVATION_AT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void activate_should_reject_ANONYMIZED_account() {
        Account account =
                Account.reconstitute(new AccountId(1L), PHONE, AccountStatus.ANONYMIZED, CREATED_AT, CREATED_AT);

        assertThatThrownBy(() -> AccountStateMachine.activate(account, ACTIVATION_AT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void activate_should_reject_null_account() {
        assertThatThrownBy(() -> AccountStateMachine.activate(null, ACTIVATION_AT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void activate_should_reject_null_at() {
        Account account = new Account(PHONE, CREATED_AT);
        assertThatThrownBy(() -> AccountStateMachine.activate(account, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void canLogin_should_return_true_when_status_is_ACTIVE() {
        Account account = Account.reconstitute(new AccountId(1L), PHONE, AccountStatus.ACTIVE, CREATED_AT, CREATED_AT);

        assertThat(AccountStateMachine.canLogin(account)).isTrue();
    }

    @Test
    void canLogin_should_return_false_when_status_is_FROZEN() {
        Account account = Account.reconstitute(new AccountId(1L), PHONE, AccountStatus.FROZEN, CREATED_AT, CREATED_AT);

        assertThat(AccountStateMachine.canLogin(account)).isFalse();
    }

    @Test
    void canLogin_should_return_false_when_status_is_ANONYMIZED() {
        Account account =
                Account.reconstitute(new AccountId(1L), PHONE, AccountStatus.ANONYMIZED, CREATED_AT, CREATED_AT);

        assertThat(AccountStateMachine.canLogin(account)).isFalse();
    }

    @Test
    void canLogin_should_return_false_when_status_is_null() {
        Account account = new Account(PHONE, CREATED_AT); // status=null

        assertThat(AccountStateMachine.canLogin(account)).isFalse();
    }

    @Test
    void canLogin_should_reject_null_account() {
        assertThatThrownBy(() -> AccountStateMachine.canLogin(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void changeDisplayName_should_set_displayName_and_updatedAt_when_ACTIVE() {
        Account account = Account.reconstitute(
                new AccountId(1L), PHONE, AccountStatus.ACTIVE, CREATED_AT, CREATED_AT, /* lastLoginAt= */ null);
        DisplayName name = new DisplayName("Alice");
        Instant updateAt = CREATED_AT.plusSeconds(60);

        Account result = AccountStateMachine.changeDisplayName(account, name, updateAt);

        assertThat(result).isSameAs(account);
        assertThat(account.displayName()).isEqualTo(name);
        assertThat(account.updatedAt()).isEqualTo(updateAt);
    }

    @Test
    void changeDisplayName_should_be_idempotent_on_same_value() {
        Account account = Account.reconstitute(
                new AccountId(1L),
                PHONE,
                AccountStatus.ACTIVE,
                CREATED_AT,
                CREATED_AT,
                /* lastLoginAt= */ null,
                new DisplayName("Alice"));
        DisplayName same = new DisplayName("Alice");
        Instant updateAt = CREATED_AT.plusSeconds(120);

        AccountStateMachine.changeDisplayName(account, same, updateAt);

        assertThat(account.displayName()).isEqualTo(same);
        assertThat(account.updatedAt()).isEqualTo(updateAt);
    }

    @Test
    void changeDisplayName_should_reject_FROZEN_account() {
        Account account = Account.reconstitute(
                new AccountId(1L), PHONE, AccountStatus.FROZEN, CREATED_AT, CREATED_AT, /* lastLoginAt= */ null);

        assertThatThrownBy(() -> AccountStateMachine.changeDisplayName(
                        account, new DisplayName("Alice"), CREATED_AT.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FROZEN");
    }

    @Test
    void changeDisplayName_should_reject_ANONYMIZED_account() {
        Account account = Account.reconstitute(
                new AccountId(1L), PHONE, AccountStatus.ANONYMIZED, CREATED_AT, CREATED_AT, /* lastLoginAt= */ null);

        assertThatThrownBy(() -> AccountStateMachine.changeDisplayName(
                        account, new DisplayName("Alice"), CREATED_AT.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANONYMIZED");
    }

    @Test
    void changeDisplayName_should_reject_null_arguments() {
        Account account = Account.reconstitute(
                new AccountId(1L), PHONE, AccountStatus.ACTIVE, CREATED_AT, CREATED_AT, /* lastLoginAt= */ null);
        DisplayName name = new DisplayName("Alice");
        Instant at = CREATED_AT.plusSeconds(60);

        assertThatThrownBy(() -> AccountStateMachine.changeDisplayName(null, name, at))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AccountStateMachine.changeDisplayName(account, null, at))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AccountStateMachine.changeDisplayName(account, name, null))
                .isInstanceOf(NullPointerException.class);
    }

    // --- T2: markFrozen (delete-account M1.3) ---

    @Test
    void markFrozen_should_transition_to_FROZEN_and_return_same_account() {
        Account account = Account.reconstitute(new AccountId(1L), PHONE, AccountStatus.ACTIVE, CREATED_AT, CREATED_AT);
        Instant freezeUntil = ACTIVATION_AT.plusSeconds(15L * 24 * 3600);

        Account result = AccountStateMachine.markFrozen(account, freezeUntil, ACTIVATION_AT);

        assertThat(result).isSameAs(account);
        assertThat(account.status()).isEqualTo(AccountStatus.FROZEN);
        assertThat(account.freezeUntil()).isEqualTo(freezeUntil);
        assertThat(account.updatedAt()).isEqualTo(ACTIVATION_AT);
    }

    @Test
    void markFrozen_should_reject_non_ACTIVE_account() {
        Account frozen = Account.reconstitute(new AccountId(1L), PHONE, AccountStatus.FROZEN, CREATED_AT, CREATED_AT);

        assertThatThrownBy(
                        () -> AccountStateMachine.markFrozen(frozen, ACTIVATION_AT.plusSeconds(1_000), ACTIVATION_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    void markFrozen_should_reject_null_account() {
        assertThatThrownBy(() -> AccountStateMachine.markFrozen(null, ACTIVATION_AT.plusSeconds(1_000), ACTIVATION_AT))
                .isInstanceOf(NullPointerException.class);
    }
}
