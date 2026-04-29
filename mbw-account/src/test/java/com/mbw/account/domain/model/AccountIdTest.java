package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountIdTest {

    @Test
    void should_accept_positive_long() {
        AccountId id = new AccountId(42L);

        assertThat(id.value()).isEqualTo(42L);
    }

    @Test
    void should_accept_max_long() {
        AccountId id = new AccountId(Long.MAX_VALUE);

        assertThat(id.value()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void should_reject_zero() {
        assertThatThrownBy(() -> new AccountId(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AccountId must be positive");
    }

    @Test
    void should_reject_negative_value() {
        assertThatThrownBy(() -> new AccountId(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AccountId must be positive");
    }

    @Test
    void equality_should_match_value() {
        AccountId a = new AccountId(7L);
        AccountId b = new AccountId(7L);
        AccountId c = new AccountId(8L);

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }
}
