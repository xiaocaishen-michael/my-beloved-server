package com.mbw.account.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PhoneHasherTest {

    @Test
    void should_return_64_char_hex_for_valid_phone() {
        String hash = PhoneHasher.sha256Hex("+8613800138000");

        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void should_be_idempotent_for_same_input() {
        String first = PhoneHasher.sha256Hex("+8613800138000");
        String second = PhoneHasher.sha256Hex("+8613800138000");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void should_differ_for_different_phones() {
        String hashA = PhoneHasher.sha256Hex("+8613800138000");
        String hashB = PhoneHasher.sha256Hex("+8613800138001");

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void should_throw_when_phone_null() {
        assertThatThrownBy(() -> PhoneHasher.sha256Hex(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("phone");
    }

    @Test
    void should_throw_when_phone_blank() {
        assertThatThrownBy(() -> PhoneHasher.sha256Hex(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phone must not be blank");
    }

    @Test
    void should_match_known_test_vector() {
        // Ground truth from `printf '%s' '+8613800138000' | shasum -a 256` —
        // pinning the impl to a stable SHA-256 hex output (UTF-8 encoded
        // input). Drift in encoding or algorithm flips this assertion.
        assertThat(PhoneHasher.sha256Hex("+8613800138000"))
                .isEqualTo("ec61f3c620a98bdead8c1f1f0ae747abd1b62a0c2dba4fd4bc22cf0d1d8653e5");
    }
}
