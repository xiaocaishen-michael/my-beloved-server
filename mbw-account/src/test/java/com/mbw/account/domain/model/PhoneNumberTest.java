package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PhoneNumberTest {

    @Test
    void should_accept_valid_mainland_E164_number() {
        PhoneNumber phone = new PhoneNumber("+8613800138000");

        assertThat(phone.e164()).isEqualTo("+8613800138000");
        assertThat(phone.countryCode()).isEqualTo("+86");
        assertThat(phone.nationalNumber()).isEqualTo("13800138000");
    }

    @Test
    void should_accept_all_mainland_carrier_prefixes_13_to_19() {
        new PhoneNumber("+8613000000000");
        new PhoneNumber("+8614000000000");
        new PhoneNumber("+8615000000000");
        new PhoneNumber("+8616000000000");
        new PhoneNumber("+8617000000000");
        new PhoneNumber("+8618000000000");
        new PhoneNumber("+8619000000000");
    }

    @Test
    void should_reject_number_without_plus_prefix() {
        assertThatThrownBy(() -> new PhoneNumber("8613800138000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_PHONE_FORMAT");
    }

    @Test
    void should_reject_non_mainland_country_code() {
        assertThatThrownBy(() -> new PhoneNumber("+12025550100"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_PHONE_FORMAT");
    }

    @Test
    void should_reject_invalid_carrier_prefix() {
        assertThatThrownBy(() -> new PhoneNumber("+8612000000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_PHONE_FORMAT");
    }

    @Test
    void should_reject_too_short_number() {
        assertThatThrownBy(() -> new PhoneNumber("+861380013800"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_PHONE_FORMAT");
    }

    @Test
    void should_reject_too_long_number() {
        assertThatThrownBy(() -> new PhoneNumber("+861380013800012"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_PHONE_FORMAT");
    }

    @Test
    void should_reject_letters_in_number() {
        assertThatThrownBy(() -> new PhoneNumber("+861380013800a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_PHONE_FORMAT");
    }

    @Test
    void should_reject_null() {
        assertThatThrownBy(() -> new PhoneNumber(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_reject_empty_string() {
        assertThatThrownBy(() -> new PhoneNumber(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_PHONE_FORMAT");
    }
}
