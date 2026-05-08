package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeviceNameTest {

    @Test
    void should_accept_short_name() {
        DeviceName name = new DeviceName("MK-iPhone");

        assertThat(name.value()).isEqualTo("MK-iPhone");
    }

    @Test
    void should_trim_surrounding_whitespace() {
        DeviceName name = new DeviceName("  MK-iPhone  ");

        assertThat(name.value()).isEqualTo("MK-iPhone");
    }

    @Test
    void should_accept_chinese_and_emoji_within_64_chars() {
        DeviceName name = new DeviceName("张磊的 Mate 50 📱");

        assertThat(name.value()).isEqualTo("张磊的 Mate 50 📱");
    }

    @Test
    void should_accept_64_char_boundary() {
        String exactly64 = "a".repeat(64);

        DeviceName name = new DeviceName(exactly64);

        assertThat(name.value()).hasSize(64);
    }

    @Test
    void should_reject_over_64_chars() {
        String tooLong = "a".repeat(65);

        assertThatThrownBy(() -> new DeviceName(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_DEVICE_NAME");
    }

    @Test
    void should_reject_blank_string() {
        assertThatThrownBy(() -> new DeviceName("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_DEVICE_NAME");
    }

    @Test
    void should_reject_null() {
        assertThatThrownBy(() -> new DeviceName(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofNullable_should_return_null_when_input_null() {
        assertThat(DeviceName.ofNullable(null)).isNull();
    }

    @Test
    void ofNullable_should_return_null_when_input_blank() {
        assertThat(DeviceName.ofNullable("   ")).isNull();
    }

    @Test
    void ofNullable_should_wrap_non_blank_value() {
        DeviceName name = DeviceName.ofNullable(" MK-iPhone ");

        assertThat(name).isNotNull();
        assertThat(name.value()).isEqualTo("MK-iPhone");
    }
}
