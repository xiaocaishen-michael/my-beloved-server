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
}
