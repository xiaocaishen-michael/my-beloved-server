package com.mbw.account.domain.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mbw.account.domain.exception.WeakPasswordException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void should_accept_password_meeting_all_requirements() {
        assertThatCode(() -> PasswordPolicy.validate("Abcdef12")).doesNotThrowAnyException();
        assertThatCode(() -> PasswordPolicy.validate("MyStrongP4ss")).doesNotThrowAnyException();
        assertThatCode(() -> PasswordPolicy.validate("Aa1bcdefghijklmnopqrstuvwxyz"))
                .doesNotThrowAnyException();
    }

    @Test
    void should_reject_too_short_password() {
        assertThatThrownBy(() -> PasswordPolicy.validate("Abc1"))
                .isInstanceOf(WeakPasswordException.class)
                .hasMessageContaining("INVALID_PASSWORD")
                .hasMessageContaining("8");
    }

    @Test
    void should_reject_password_at_one_below_minimum_length() {
        assertThatThrownBy(() -> PasswordPolicy.validate("Abcdef1"))
                .isInstanceOf(WeakPasswordException.class)
                .hasMessageContaining("8");
    }

    @Test
    void should_accept_password_at_exactly_minimum_length() {
        assertThatCode(() -> PasswordPolicy.validate("Abcdef12")).doesNotThrowAnyException();
    }

    @Test
    void should_reject_password_without_uppercase() {
        assertThatThrownBy(() -> PasswordPolicy.validate("abcdef12"))
                .isInstanceOf(WeakPasswordException.class)
                .hasMessageContaining("uppercase");
    }

    @Test
    void should_reject_password_without_lowercase() {
        assertThatThrownBy(() -> PasswordPolicy.validate("ABCDEF12"))
                .isInstanceOf(WeakPasswordException.class)
                .hasMessageContaining("lowercase");
    }

    @Test
    void should_reject_password_without_digit() {
        assertThatThrownBy(() -> PasswordPolicy.validate("Abcdefgh"))
                .isInstanceOf(WeakPasswordException.class)
                .hasMessageContaining("digit");
    }

    @Test
    void should_reject_null_password() {
        assertThatThrownBy(() -> PasswordPolicy.validate(null)).isInstanceOf(WeakPasswordException.class);
    }

    @Test
    void should_reject_empty_password() {
        assertThatThrownBy(() -> PasswordPolicy.validate("")).isInstanceOf(WeakPasswordException.class);
    }

    @Test
    void should_accept_password_with_special_characters_when_other_requirements_met() {
        assertThatCode(() -> PasswordPolicy.validate("Abcd1234!@#")).doesNotThrowAnyException();
    }
}
