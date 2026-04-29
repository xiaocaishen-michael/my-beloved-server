package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VerificationCodeTest {

    @Test
    void should_accept_valid_six_digit_code() {
        VerificationCode code = new VerificationCode("123456");

        assertThat(code.value()).isEqualTo("123456");
    }

    @Test
    void should_accept_leading_zeros() {
        VerificationCode code = new VerificationCode("000000");

        assertThat(code.value()).isEqualTo("000000");
    }

    @Test
    void should_reject_too_short_code() {
        assertThatThrownBy(() -> new VerificationCode("12345"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_CODE_FORMAT");
    }

    @Test
    void should_reject_too_long_code() {
        assertThatThrownBy(() -> new VerificationCode("1234567"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_CODE_FORMAT");
    }

    @Test
    void should_reject_non_digit_characters() {
        assertThatThrownBy(() -> new VerificationCode("12345a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_CODE_FORMAT");
    }

    @Test
    void should_reject_null() {
        assertThatThrownBy(() -> new VerificationCode(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_reject_empty_string() {
        assertThatThrownBy(() -> new VerificationCode(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_CODE_FORMAT");
    }
}
