package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RefreshTokenHashTest {

    private static final String VALID_HEX =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"; // SHA-256("abc")

    @Test
    void should_accept_valid_64_char_lowercase_hex() {
        RefreshTokenHash hash = new RefreshTokenHash(VALID_HEX);

        assertThat(hash.value()).isEqualTo(VALID_HEX);
    }

    @Test
    void should_reject_when_value_is_null() {
        assertThatThrownBy(() -> new RefreshTokenHash(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_reject_when_value_is_blank() {
        assertThatThrownBy(() -> new RefreshTokenHash(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> new RefreshTokenHash("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void should_reject_when_value_length_not_64() {
        assertThatThrownBy(() -> new RefreshTokenHash(VALID_HEX.substring(0, 63)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshTokenHash(VALID_HEX + "0")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_reject_when_value_contains_uppercase() {
        String upper = VALID_HEX.toUpperCase();

        assertThatThrownBy(() -> new RefreshTokenHash(upper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowercase");
    }

    @Test
    void should_reject_when_value_contains_non_hex_chars() {
        // Replace one char with 'g' (not hex)
        String withNonHex = VALID_HEX.substring(0, 63) + "g";

        assertThatThrownBy(() -> new RefreshTokenHash(withNonHex)).isInstanceOf(IllegalArgumentException.class);
    }
}
