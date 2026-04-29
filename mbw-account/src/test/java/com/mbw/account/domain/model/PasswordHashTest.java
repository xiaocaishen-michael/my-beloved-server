package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordHashTest {

    private static final String VALID_BCRYPT_2A = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final String VALID_BCRYPT_2B = "$2b$08$KkS4dkLnVfwPqx0q2jPBXuO1ovmCH6F0NNyDJ.HhoMIH4wUJEHASq";

    @Test
    void should_accept_valid_bcrypt_2a_hash() {
        PasswordHash hash = new PasswordHash(VALID_BCRYPT_2A);

        assertThat(hash.value()).isEqualTo(VALID_BCRYPT_2A);
    }

    @Test
    void should_accept_valid_bcrypt_2b_hash() {
        PasswordHash hash = new PasswordHash(VALID_BCRYPT_2B);

        assertThat(hash.value()).isEqualTo(VALID_BCRYPT_2B);
    }

    @Test
    void should_reject_plaintext_password() {
        assertThatThrownBy(() -> new PasswordHash("MyPassword123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_PASSWORD_HASH");
    }

    @Test
    void should_reject_truncated_bcrypt_hash() {
        String truncated = VALID_BCRYPT_2A.substring(0, 50);
        assertThatThrownBy(() -> new PasswordHash(truncated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_PASSWORD_HASH");
    }

    @Test
    void should_reject_unsupported_bcrypt_variant() {
        String fakeVariant = "$1a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
        assertThatThrownBy(() -> new PasswordHash(fakeVariant))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_PASSWORD_HASH");
    }

    @Test
    void should_reject_null() {
        assertThatThrownBy(() -> new PasswordHash(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_reject_empty_string() {
        assertThatThrownBy(() -> new PasswordHash(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_PASSWORD_HASH");
    }
}
