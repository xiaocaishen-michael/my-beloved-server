package com.mbw.account.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mbw.account.domain.exception.InvalidPhoneFormatException;
import com.mbw.account.domain.model.PhoneNumber;
import org.junit.jupiter.api.Test;

class PhonePolicyTest {

    @Test
    void should_return_PhoneNumber_when_format_is_valid_mainland_E164() {
        PhoneNumber phone = PhonePolicy.validate("+8613800138000");

        assertThat(phone.e164()).isEqualTo("+8613800138000");
    }

    @Test
    void should_throw_InvalidPhoneFormatException_for_non_mainland_country_code() {
        assertThatThrownBy(() -> PhonePolicy.validate("+12025550100"))
                .isInstanceOf(InvalidPhoneFormatException.class)
                .hasMessageContaining("INVALID_PHONE_FORMAT")
                .hasMessageContaining("+12025550100");
    }

    @Test
    void should_throw_InvalidPhoneFormatException_for_missing_plus_prefix() {
        assertThatThrownBy(() -> PhonePolicy.validate("8613800138000"))
                .isInstanceOf(InvalidPhoneFormatException.class)
                .hasMessageContaining("INVALID_PHONE_FORMAT");
    }

    @Test
    void should_throw_InvalidPhoneFormatException_for_too_short_number() {
        assertThatThrownBy(() -> PhonePolicy.validate("+861380013800")).isInstanceOf(InvalidPhoneFormatException.class);
    }

    @Test
    void should_throw_InvalidPhoneFormatException_for_letters_in_number() {
        assertThatThrownBy(() -> PhonePolicy.validate("+861380013800a"))
                .isInstanceOf(InvalidPhoneFormatException.class);
    }

    @Test
    void should_throw_InvalidPhoneFormatException_for_null_input() {
        assertThatThrownBy(() -> PhonePolicy.validate(null)).isInstanceOf(InvalidPhoneFormatException.class);
    }

    @Test
    void should_throw_InvalidPhoneFormatException_for_empty_string() {
        assertThatThrownBy(() -> PhonePolicy.validate("")).isInstanceOf(InvalidPhoneFormatException.class);
    }

    @Test
    void exception_should_carry_submitted_phone_for_logging() {
        try {
            PhonePolicy.validate("+12025550100");
        } catch (InvalidPhoneFormatException e) {
            assertThat(e.getSubmittedPhone()).isEqualTo("+12025550100");
        }
    }
}
