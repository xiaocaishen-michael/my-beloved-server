package com.mbw.account.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.account.domain.model.RefreshTokenHash;
import org.junit.jupiter.api.Test;

class RefreshTokenHasherTest {

    @Test
    void should_produce_64_char_lowercase_hex() {
        RefreshTokenHash hash = RefreshTokenHasher.hash("any-input");

        assertThat(hash.value()).hasSize(64).matches("^[0-9a-f]{64}$");
    }

    @Test
    void should_be_deterministic_for_same_input() {
        RefreshTokenHash a = RefreshTokenHasher.hash("same-input");
        RefreshTokenHash b = RefreshTokenHasher.hash("same-input");

        assertThat(a).isEqualTo(b);
    }

    @Test
    void should_produce_distinct_hashes_for_distinct_inputs() {
        RefreshTokenHash a = RefreshTokenHasher.hash("input-A");
        RefreshTokenHash b = RefreshTokenHasher.hash("input-B");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_match_known_sha256_vector() {
        // RFC standard SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        RefreshTokenHash hash = RefreshTokenHasher.hash("abc");

        assertThat(hash.value()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
