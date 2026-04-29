package com.mbw.account.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.account.domain.model.PasswordHash;
import org.junit.jupiter.api.Test;

class BCryptPasswordHasherTest {

    private final BCryptPasswordHasher hasher = new BCryptPasswordHasher();

    @Test
    void hash_should_produce_a_valid_PasswordHash_for_plaintext() {
        PasswordHash hash = hasher.hash("MyStrongP4ss");

        assertThat(hash.value()).startsWith("$2");
        assertThat(hash.value()).hasSize(60);
    }

    @Test
    void hash_should_be_non_deterministic_due_to_random_salt() {
        PasswordHash a = hasher.hash("MyStrongP4ss");
        PasswordHash b = hasher.hash("MyStrongP4ss");

        assertThat(a.value()).isNotEqualTo(b.value());
    }

    @Test
    void matches_should_return_true_for_correct_plaintext() {
        PasswordHash hash = hasher.hash("MyStrongP4ss");

        assertThat(hasher.matches("MyStrongP4ss", hash)).isTrue();
    }

    @Test
    void matches_should_return_false_for_wrong_plaintext() {
        PasswordHash hash = hasher.hash("MyStrongP4ss");

        assertThat(hasher.matches("WrongP4ssword", hash)).isFalse();
    }

    @Test
    void matches_should_return_false_for_case_difference() {
        PasswordHash hash = hasher.hash("MyStrongP4ss");

        assertThat(hasher.matches("mystrongp4ss", hash)).isFalse();
    }
}
