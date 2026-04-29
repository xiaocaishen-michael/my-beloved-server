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
}
