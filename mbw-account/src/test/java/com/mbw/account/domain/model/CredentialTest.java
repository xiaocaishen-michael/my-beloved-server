package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CredentialTest {

    private static final AccountId ACCOUNT = new AccountId(42L);
    private static final PhoneNumber PHONE = new PhoneNumber("+8613800138000");
    private static final PasswordHash HASH =
            new PasswordHash("$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy");
    private static final Instant NOW = Instant.parse("2026-04-29T01:00:00Z");

    @Test
    void Credential_should_be_sealed_with_two_permitted_subtypes() {
        Class<?>[] permitted = Credential.class.getPermittedSubclasses();

        assertThat(permitted).containsExactlyInAnyOrder(PhoneCredential.class, PasswordCredential.class);
    }

    @Test
    void PhoneCredential_should_implement_Credential() {
        PhoneCredential credential = new PhoneCredential(ACCOUNT, PHONE, NOW);

        assertThat(credential).isInstanceOf(Credential.class);
        assertThat(credential.account()).isEqualTo(ACCOUNT);
        assertThat(credential.phone()).isEqualTo(PHONE);
        assertThat(credential.lastUsedAt()).isEqualTo(NOW);
    }

    @Test
    void PasswordCredential_should_implement_Credential() {
        PasswordCredential credential = new PasswordCredential(ACCOUNT, HASH, NOW);

        assertThat(credential).isInstanceOf(Credential.class);
        assertThat(credential.account()).isEqualTo(ACCOUNT);
        assertThat(credential.hash()).isEqualTo(HASH);
        assertThat(credential.createdAt()).isEqualTo(NOW);
    }

    @Test
    void PhoneCredential_should_reject_null_account() {
        assertThatThrownBy(() -> new PhoneCredential(null, PHONE, NOW)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void PhoneCredential_should_reject_null_phone() {
        assertThatThrownBy(() -> new PhoneCredential(ACCOUNT, null, NOW)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void PhoneCredential_should_reject_null_lastUsedAt() {
        assertThatThrownBy(() -> new PhoneCredential(ACCOUNT, PHONE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void PasswordCredential_should_reject_null_account() {
        assertThatThrownBy(() -> new PasswordCredential(null, HASH, NOW)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void PasswordCredential_should_reject_null_hash() {
        assertThatThrownBy(() -> new PasswordCredential(ACCOUNT, null, NOW)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void PasswordCredential_should_reject_null_createdAt() {
        assertThatThrownBy(() -> new PasswordCredential(ACCOUNT, HASH, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_be_pattern_matchable_via_switch() {
        Credential phoneCred = new PhoneCredential(ACCOUNT, PHONE, NOW);
        Credential passwordCred = new PasswordCredential(ACCOUNT, HASH, NOW);

        String phoneType = describe(phoneCred);
        String passwordType = describe(passwordCred);

        assertThat(phoneType).isEqualTo("PHONE");
        assertThat(passwordType).isEqualTo("PASSWORD");
    }

    private static String describe(Credential credential) {
        return switch (credential) {
            case PhoneCredential ignored -> "PHONE";
            case PasswordCredential ignored -> "PASSWORD";
        };
    }
}
