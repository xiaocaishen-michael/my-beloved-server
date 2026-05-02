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
}
