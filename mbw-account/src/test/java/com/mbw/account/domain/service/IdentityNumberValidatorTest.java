package com.mbw.account.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link IdentityNumberValidator} (realname-verification spec T2).
 *
 * <p>Mainland-PRC 18-digit ID card (GB 11643) validation: length, character set,
 * administrative-division prefix, calendar-date sanity, and check-digit. All
 * negative cases are exhaustively pinned so the validator can never silently
 * pass a malformed value into downstream encryption / hashing.
 */
class IdentityNumberValidatorTest {

    @Test
    void valid_test_id_card_with_numeric_check_digit_returns_true() {
        // GB 11643: digits=[1,1,0,1,0,1,1,9,9,0,0,1,0,1,1,2,3], weighted sum=126,
        // 126 % 11 = 5, check-table[5] = '7' — last char '7' matches → valid.
        assertThat(IdentityNumberValidator.validate("110101199001011237")).isTrue();
    }

    @Test
    void valid_id_card_with_x_check_digit_returns_true() {
        // GB 11643 mapping: when sum%11 == 2, check char is 'X'.
        // digits=[1,1,0,1,0,1,1,9,9,0,0,1,0,1,0,0,4], weighted sum=112,
        // 112 % 11 = 2, check-table[2] = 'X' — last char 'X' matches → valid.
        assertThat(IdentityNumberValidator.validate("11010119900101004X")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1101011990010112", "1101011990010112370"})
    void wrong_length_returns_false(String input) {
        // 17 chars / 19 chars — anything but 18 is invalid by length.
        assertThat(IdentityNumberValidator.validate(input)).isFalse();
    }

    @Test
    void non_numeric_in_first_17_chars_returns_false() {
        // Letter 'A' in position 11 — only the trailing check digit may be 'X'.
        assertThat(IdentityNumberValidator.validate("11010119900A011237")).isFalse();
    }

    @Test
    void wrong_check_digit_returns_false() {
        // Same prefix as the happy-path number but trailing digit '0' instead of '7'
        // — GB 11643 expects '7' for this prefix, so '0' is a check-digit mismatch.
        assertThat(IdentityNumberValidator.validate("110101199001011230")).isFalse();
    }

    @Test
    void administrative_division_starting_with_00_returns_false() {
        // Administrative-division code (first 2 digits) must not start with '00'
        // — no PRC province uses prefix 00.
        assertThat(IdentityNumberValidator.validate("001010199001011230")).isFalse();
    }

    @Test
    void invalid_calendar_date_returns_false() {
        // Birth date encoded as Feb 30 — calendar-impossible.
        assertThat(IdentityNumberValidator.validate("110101199002301234")).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void null_or_blank_returns_false(String input) {
        assertThat(IdentityNumberValidator.validate(input)).isFalse();
    }
}
