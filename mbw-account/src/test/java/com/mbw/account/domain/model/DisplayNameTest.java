package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DisplayNameTest {

    @Test
    void should_reject_null() {
        assertThatThrownBy(() -> new DisplayName(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");
    }

    @Test
    void should_reject_empty_string() {
        assertThatThrownBy(() -> new DisplayName(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_DISPLAY_NAME");
    }

    @Test
    void should_reject_whitespace_only() {
        assertThatThrownBy(() -> new DisplayName("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_DISPLAY_NAME");
    }

    @Test
    void should_reject_control_character_BEL() {
        String input = "x" + (char) 0x0007 + "y";

        assertThatThrownBy(() -> new DisplayName(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_DISPLAY_NAME");
    }

    @Test
    void should_reject_zero_width_space() {
        String input = "x" + (char) 0x200B + "y";

        assertThatThrownBy(() -> new DisplayName(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_DISPLAY_NAME");
    }

    @Test
    void should_reject_line_separator() {
        String input = "x" + (char) 0x2028 + "y";

        assertThatThrownBy(() -> new DisplayName(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_DISPLAY_NAME");
    }

    @Test
    void should_reject_length_above_32_codepoints() {
        String thirtyThreeAscii = "a".repeat(33);

        assertThatThrownBy(() -> new DisplayName(thirtyThreeAscii))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_DISPLAY_NAME");
    }

    @Test
    void should_accept_length_32_cjk_characters() {
        String thirtyTwoCjk = "小".repeat(32);

        DisplayName name = new DisplayName(thirtyTwoCjk);

        assertThat(name.value()).isEqualTo(thirtyTwoCjk);
        assertThat(name.value().codePointCount(0, name.value().length())).isEqualTo(32);
    }

    @Test
    void should_accept_emoji_only_counted_by_codepoint() {
        String emoji = new String(Character.toChars(0x1F389)) + new String(Character.toChars(0x1F338));

        DisplayName name = new DisplayName(emoji);

        assertThat(name.value()).isEqualTo(emoji);
        assertThat(name.value().codePointCount(0, name.value().length())).isEqualTo(2);
    }

    @Test
    void should_accept_mixed_ascii_emoji() {
        String mixed = "Hello_2026" + new String(Character.toChars(0x1F389));

        DisplayName name = new DisplayName(mixed);

        assertThat(name.value()).isEqualTo(mixed);
    }

    @Test
    void should_trim_leading_and_trailing_whitespace() {
        DisplayName name = new DisplayName("  Hello  ");

        assertThat(name.value()).isEqualTo("Hello");
    }
}
