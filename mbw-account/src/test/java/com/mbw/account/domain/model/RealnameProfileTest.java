package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link RealnameProfile} aggregate root
 * (realname-verification spec T1).
 *
 * <p>Domain-only — zero framework / persistence imports. Each test pins a
 * single invariant required by spec.md FR-001..FR-015 + plan.md "数据模型变更".
 */
class RealnameProfileTest {

    private static final long ACCOUNT_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-05-08T10:00:00Z");

    @Test
    void unverified_factory_creates_profile_with_only_account_id_and_timestamps() {
        RealnameProfile profile = RealnameProfile.unverified(ACCOUNT_ID, NOW);

        assertThat(profile.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(profile.status()).isEqualTo(RealnameStatus.UNVERIFIED);
        // All sensitive fields null in UNVERIFIED state — partial unique index on
        // id_card_hash relies on this; logging-leak IT (T18) also depends on it.
        assertThat(profile.realNameEnc()).isNull();
        assertThat(profile.idCardNoEnc()).isNull();
        assertThat(profile.idCardHash()).isNull();
        assertThat(profile.providerBizId()).isNull();
        assertThat(profile.verifiedAt()).isNull();
        assertThat(profile.failedReason()).isNull();
        assertThat(profile.failedAt()).isNull();
        assertThat(profile.retryCount24h()).isZero();
        assertThat(profile.createdAt()).isEqualTo(NOW);
        assertThat(profile.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void withPending_writes_encrypted_fields_and_advances_status() {
        RealnameProfile unverified = RealnameProfile.unverified(ACCOUNT_ID, NOW);
        byte[] realNameEnc = new byte[] {1, 2, 3};
        byte[] idCardNoEnc = new byte[] {4, 5, 6};
        String idCardHash = "a".repeat(64);
        String bizId = "biz-001";
        Instant later = NOW.plusSeconds(60);

        RealnameProfile pending = unverified.withPending(realNameEnc, idCardNoEnc, idCardHash, bizId, later);

        assertThat(pending.status()).isEqualTo(RealnameStatus.PENDING);
        assertThat(pending.realNameEnc()).isEqualTo(realNameEnc);
        assertThat(pending.idCardNoEnc()).isEqualTo(idCardNoEnc);
        assertThat(pending.idCardHash()).isEqualTo(idCardHash);
        assertThat(pending.providerBizId()).isEqualTo(bizId);
        // createdAt frozen, updatedAt advances
        assertThat(pending.createdAt()).isEqualTo(NOW);
        assertThat(pending.updatedAt()).isEqualTo(later);
        // failure-side fields stay null in PENDING
        assertThat(pending.verifiedAt()).isNull();
        assertThat(pending.failedReason()).isNull();
        assertThat(pending.failedAt()).isNull();
        // retry counter unchanged on initiate (only failure increments)
        assertThat(pending.retryCount24h()).isZero();
    }

    @Test
    void withVerified_advances_to_terminal_state_and_clears_failure_fields() {
        RealnameProfile pending = RealnameProfile.unverified(ACCOUNT_ID, NOW)
                .withPending(new byte[] {1}, new byte[] {2}, "h".repeat(64), "biz-002", NOW.plusSeconds(60));
        // simulate a prior failure by going FAILED then back through PENDING; retry counter should not survive VERIFIED
        Instant failedAt = NOW.plusSeconds(120);
        RealnameProfile failed = pending.withFailed(FailedReason.NAME_ID_MISMATCH, failedAt);
        RealnameProfile retryPending =
                failed.withPending(new byte[] {1}, new byte[] {2}, "h".repeat(64), "biz-003", NOW.plusSeconds(180));
        Instant verifiedAt = NOW.plusSeconds(240);

        RealnameProfile verified = retryPending.withVerified(verifiedAt);

        assertThat(verified.status()).isEqualTo(RealnameStatus.VERIFIED);
        assertThat(verified.verifiedAt()).isEqualTo(verifiedAt);
        // FR-007 / D-001: terminal — failure trace cleared (provider re-tries don't drag old failure metadata)
        assertThat(verified.failedReason()).isNull();
        assertThat(verified.failedAt()).isNull();
        assertThat(verified.updatedAt()).isEqualTo(verifiedAt);
        // ciphertext + hash + bizId preserved (UseCase needs them for QueryRealnameStatus mask path)
        assertThat(verified.idCardHash()).isEqualTo("h".repeat(64));
        assertThat(verified.providerBizId()).isEqualTo("biz-003");
    }

    @Test
    void maskRealName_two_char_replaces_first_with_star() {
        assertThat(RealnameProfile.maskRealName("张三")).isEqualTo("*三");
    }

    @Test
    void maskRealName_three_char_replaces_first_two_with_stars() {
        assertThat(RealnameProfile.maskRealName("张小明")).isEqualTo("**明");
    }

    @Test
    void maskRealName_four_char_replaces_first_three_with_stars() {
        assertThat(RealnameProfile.maskRealName("欧阳询初")).isEqualTo("***初");
    }

    @Test
    void maskIdCardNo_keeps_first_and_last_with_16_stars_in_between() {
        // 18-digit ID card → first char + 16 stars + last char
        String masked = RealnameProfile.maskIdCardNo("110101199001011237");

        assertThat(masked).isEqualTo("1****************7");
        assertThat(masked).hasSize(18);
    }

    @Test
    void withVerified_from_unverified_directly_throws_illegal_state() {
        // UNVERIFIED → VERIFIED is illegal — must traverse PENDING first
        // (T3 RealnameStateMachine will own the full transition matrix; T1 enforces inline)
        RealnameProfile unverified = RealnameProfile.unverified(ACCOUNT_ID, NOW);

        assertThatThrownBy(() -> unverified.withVerified(NOW.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withPending_with_null_realNameEnc_throws_npe() {
        RealnameProfile unverified = RealnameProfile.unverified(ACCOUNT_ID, NOW);

        assertThatThrownBy(() -> unverified.withPending(
                        /* realNameEnc= */ null, new byte[] {2}, "h".repeat(64), "biz", NOW.plusSeconds(60)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void withFailed_increments_retry_counter_except_for_user_canceled_or_provider_error() {
        RealnameProfile pending = RealnameProfile.unverified(ACCOUNT_ID, NOW)
                .withPending(new byte[] {1}, new byte[] {2}, "h".repeat(64), "biz-004", NOW.plusSeconds(60));
        Instant t1 = NOW.plusSeconds(120);

        // 1st FAILED with NAME_ID_MISMATCH → counter 0 → 1
        RealnameProfile after1 = pending.withFailed(FailedReason.NAME_ID_MISMATCH, t1);
        assertThat(after1.status()).isEqualTo(RealnameStatus.FAILED);
        assertThat(after1.failedReason()).isEqualTo(FailedReason.NAME_ID_MISMATCH);
        assertThat(after1.failedAt()).isEqualTo(t1);
        assertThat(after1.retryCount24h()).isEqualTo(1);
        assertThat(after1.updatedAt()).isEqualTo(t1);

        // Re-PENDING then FAILED with USER_CANCELED → counter stays at 1 (per FR-009 / SC-005)
        RealnameProfile retry =
                after1.withPending(new byte[] {1}, new byte[] {2}, "h".repeat(64), "biz-005", NOW.plusSeconds(180));
        Instant t2 = NOW.plusSeconds(240);
        RealnameProfile after2 = retry.withFailed(FailedReason.USER_CANCELED, t2);
        assertThat(after2.status()).isEqualTo(RealnameStatus.FAILED);
        assertThat(after2.failedReason()).isEqualTo(FailedReason.USER_CANCELED);
        assertThat(after2.retryCount24h()).isEqualTo(1);

        // Re-PENDING then FAILED with PROVIDER_ERROR → counter stays at 1 (per PR-3 BASE
        // compensation: upstream / network failures outside the user's control should not
        // burn the user's 5-per-24h retry quota — same exemption as USER_CANCELED).
        RealnameProfile retry2 =
                after2.withPending(new byte[] {1}, new byte[] {2}, "h".repeat(64), "biz-006", NOW.plusSeconds(300));
        Instant t3 = NOW.plusSeconds(360);
        RealnameProfile after3 = retry2.withFailed(FailedReason.PROVIDER_ERROR, t3);
        assertThat(after3.status()).isEqualTo(RealnameStatus.FAILED);
        assertThat(after3.failedReason()).isEqualTo(FailedReason.PROVIDER_ERROR);
        assertThat(after3.retryCount24h()).isEqualTo(1);
    }
}
