package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountSmsCodePurposeTest {

    @Test
    void should_contain_PHONE_SMS_AUTH_value() {
        assertThat(AccountSmsCodePurpose.valueOf("PHONE_SMS_AUTH")).isEqualTo(AccountSmsCodePurpose.PHONE_SMS_AUTH);
    }

    @Test
    void should_contain_DELETE_ACCOUNT_value() {
        assertThat(AccountSmsCodePurpose.valueOf("DELETE_ACCOUNT")).isEqualTo(AccountSmsCodePurpose.DELETE_ACCOUNT);
    }

    @Test
    void should_contain_CANCEL_DELETION_value() {
        assertThat(AccountSmsCodePurpose.valueOf("CANCEL_DELETION")).isEqualTo(AccountSmsCodePurpose.CANCEL_DELETION);
    }

    @Test
    void should_have_exactly_three_values() {
        assertThat(AccountSmsCodePurpose.values()).hasSize(3);
    }

    @Test
    void invalid_value_should_throw_IllegalArgumentException() {
        assertThatThrownBy(() -> AccountSmsCodePurpose.valueOf("UNKNOWN")).isInstanceOf(IllegalArgumentException.class);
    }
}
