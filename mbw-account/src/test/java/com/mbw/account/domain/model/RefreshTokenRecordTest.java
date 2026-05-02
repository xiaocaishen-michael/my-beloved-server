package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefreshTokenRecordTest {

    private static final RefreshTokenHash HASH =
            new RefreshTokenHash("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    private static final AccountId ACCOUNT_ID = new AccountId(42L);
    private static final Instant CREATED_AT = Instant.parse("2026-04-29T01:00:00Z");
    private static final Instant EXPIRES_AT = CREATED_AT.plusSeconds(30L * 24 * 3600); // 30 days

    @Test
    void should_create_active_record_with_revokedAt_null_and_id_null() {
        RefreshTokenRecord record = RefreshTokenRecord.createActive(HASH, ACCOUNT_ID, EXPIRES_AT, CREATED_AT);

        assertThat(record.id()).isNull();
        assertThat(record.tokenHash()).isEqualTo(HASH);
        assertThat(record.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(record.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(record.revokedAt()).isNull();
        assertThat(record.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void should_set_revokedAt_when_revoke_called() {
        RefreshTokenRecord record = RefreshTokenRecord.createActive(HASH, ACCOUNT_ID, EXPIRES_AT, CREATED_AT);
        Instant revokedAt = CREATED_AT.plusSeconds(60);

        RefreshTokenRecord revoked = record.revoke(revokedAt);

        assertThat(revoked.revokedAt()).isEqualTo(revokedAt);
        // Original instance unchanged (immutability)
        assertThat(record.revokedAt()).isNull();
    }

    @Test
    void should_throw_when_revoke_called_twice() {
        RefreshTokenRecord record = RefreshTokenRecord.createActive(HASH, ACCOUNT_ID, EXPIRES_AT, CREATED_AT);
        RefreshTokenRecord revoked = record.revoke(CREATED_AT.plusSeconds(60));

        assertThatThrownBy(() -> revoked.revoke(CREATED_AT.plusSeconds(120)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Already revoked");
    }

    @Test
    void isActive_should_return_true_when_not_revoked_and_not_expired() {
        RefreshTokenRecord record = RefreshTokenRecord.createActive(HASH, ACCOUNT_ID, EXPIRES_AT, CREATED_AT);

        assertThat(record.isActive(CREATED_AT.plusSeconds(60))).isTrue();
    }

    @Test
    void isActive_should_return_false_when_revoked() {
        RefreshTokenRecord record = RefreshTokenRecord.createActive(HASH, ACCOUNT_ID, EXPIRES_AT, CREATED_AT)
                .revoke(CREATED_AT.plusSeconds(60));

        assertThat(record.isActive(CREATED_AT.plusSeconds(120))).isFalse();
    }

    @Test
    void isActive_should_return_false_when_expired() {
        RefreshTokenRecord record = RefreshTokenRecord.createActive(HASH, ACCOUNT_ID, EXPIRES_AT, CREATED_AT);

        // Now is exactly at expiresAt → isActive(now) requires expiresAt > now → false
        assertThat(record.isActive(EXPIRES_AT)).isFalse();
        assertThat(record.isActive(EXPIRES_AT.plusSeconds(1))).isFalse();
    }
}
