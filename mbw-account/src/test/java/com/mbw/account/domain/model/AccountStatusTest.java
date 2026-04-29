package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountStatusTest {

    @Test
    void should_define_three_lifecycle_states() {
        assertThat(AccountStatus.values())
                .containsExactly(AccountStatus.ACTIVE, AccountStatus.FROZEN, AccountStatus.ANONYMIZED);
    }

    @Test
    void should_be_resolvable_from_uppercase_string() {
        assertThat(AccountStatus.valueOf("ACTIVE")).isEqualTo(AccountStatus.ACTIVE);
        assertThat(AccountStatus.valueOf("FROZEN")).isEqualTo(AccountStatus.FROZEN);
        assertThat(AccountStatus.valueOf("ANONYMIZED")).isEqualTo(AccountStatus.ANONYMIZED);
    }
}
