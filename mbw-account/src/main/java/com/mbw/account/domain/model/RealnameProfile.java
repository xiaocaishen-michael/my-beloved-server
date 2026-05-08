package com.mbw.account.domain.model;

import com.mbw.account.domain.service.RealnameStateMachine;
import java.time.Instant;
import java.util.Objects;

/**
 * Realname-verification profile aggregate root (one row per account).
 *
 * <p>Immutable value object — every state transition returns a new instance
 * via {@code withXxx(...)}, in line with realname-verification tasks.md T1
 * "record 或 immutable class，含 withXxx() 方法做状态转换". Cipher / hash /
 * persistence concerns live in adjacent layers; this class only models the
 * domain shape + invariants.
 *
 * <p>The aggregate intentionally does <b>not</b> hold plaintext PII —
 * {@link #realNameEnc} / {@link #idCardNoEnc} are AES-GCM ciphertext bytes.
 * Mask helpers {@link #maskRealName(String)} / {@link #maskIdCardNo(String)}
 * are static so callers (decrypted in the use-case layer) can apply the
 * domain mask rule without the aggregate ever seeing plaintext.
 */
public final class RealnameProfile {

    private final Long id;
    private final long accountId;
    private final RealnameStatus status;
    private final byte[] realNameEnc;
    private final byte[] idCardNoEnc;
    private final String idCardHash;
    private final String providerBizId;
    private final Instant verifiedAt;
    private final FailedReason failedReason;
    private final Instant failedAt;
    private final int retryCount24h;
    private final Instant createdAt;
    private final Instant updatedAt;

    private RealnameProfile(
            Long id,
            long accountId,
            RealnameStatus status,
            byte[] realNameEnc,
            byte[] idCardNoEnc,
            String idCardHash,
            String providerBizId,
            Instant verifiedAt,
            FailedReason failedReason,
            Instant failedAt,
            int retryCount24h,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.accountId = accountId;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.realNameEnc = realNameEnc;
        this.idCardNoEnc = idCardNoEnc;
        this.idCardHash = idCardHash;
        this.providerBizId = providerBizId;
        this.verifiedAt = verifiedAt;
        this.failedReason = failedReason;
        this.failedAt = failedAt;
        this.retryCount24h = retryCount24h;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /**
     * Factory for an {@code UNVERIFIED} row — used the first time an account
     * is encountered by realname code paths. All sensitive fields stay null;
     * {@link #retryCount24h} starts at 0.
     */
    public static RealnameProfile unverified(long accountId, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return new RealnameProfile(
                /* id= */ null,
                accountId,
                RealnameStatus.UNVERIFIED,
                /* realNameEnc= */ null,
                /* idCardNoEnc= */ null,
                /* idCardHash= */ null,
                /* providerBizId= */ null,
                /* verifiedAt= */ null,
                /* failedReason= */ null,
                /* failedAt= */ null,
                /* retryCount24h= */ 0,
                /* createdAt= */ now,
                /* updatedAt= */ now);
    }

    public Long id() {
        return id;
    }

    public long accountId() {
        return accountId;
    }

    public RealnameStatus status() {
        return status;
    }

    public byte[] realNameEnc() {
        return realNameEnc;
    }

    public byte[] idCardNoEnc() {
        return idCardNoEnc;
    }

    public String idCardHash() {
        return idCardHash;
    }

    public String providerBizId() {
        return providerBizId;
    }

    public Instant verifiedAt() {
        return verifiedAt;
    }

    public FailedReason failedReason() {
        return failedReason;
    }

    public Instant failedAt() {
        return failedAt;
    }

    public int retryCount24h() {
        return retryCount24h;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * Mask a real name per FR-008: replace every character but the last with
     * {@code *}. Examples: {@code "张三" → "*三"}, {@code "张小明" → "**明"},
     * {@code "欧阳询初" → "***初"}.
     *
     * <p>Static because mask is a pure function of plaintext — the aggregate
     * never holds plaintext, so callers (typically the use-case layer, post
     * cipher decrypt) apply the rule via this entry point.
     */
    public static String maskRealName(String plaintext) {
        int len = plaintext.length();
        return "*".repeat(len - 1) + plaintext.charAt(len - 1);
    }

    /**
     * Mask an 18-digit ID card per FR-008: first digit + 16 stars + last digit
     * (e.g. {@code "110101199001011237" → "1****************7"}). Length-agnostic
     * but in practice always called with the GB 11643-validated 18-char form.
     */
    public static String maskIdCardNo(String plaintext) {
        int len = plaintext.length();
        return plaintext.charAt(0) + "*".repeat(len - 2) + plaintext.charAt(len - 1);
    }

    /**
     * Transition into {@link RealnameStatus#PENDING} — used by
     * {@code InitiateRealnameVerificationUseCase} after encrypting PII and
     * obtaining a {@code providerBizId} from the cloud-auth provider.
     *
     * <p>Applicable from {@code UNVERIFIED} (first attempt) or {@code FAILED}
     * (retry per FR-009). Legality is enforced by {@link RealnameStateMachine};
     * this method itself only writes fields. {@link #createdAt} is preserved;
     * {@link #updatedAt} advances to {@code now}.
     */
    public RealnameProfile withPending(
            byte[] realNameEnc, byte[] idCardNoEnc, String idCardHash, String providerBizId, Instant now) {
        Objects.requireNonNull(realNameEnc, "realNameEnc must not be null");
        Objects.requireNonNull(idCardNoEnc, "idCardNoEnc must not be null");
        Objects.requireNonNull(idCardHash, "idCardHash must not be null");
        Objects.requireNonNull(providerBizId, "providerBizId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        RealnameStateMachine.assertCanTransition(this.status, RealnameStatus.PENDING);
        return new RealnameProfile(
                this.id,
                this.accountId,
                RealnameStatus.PENDING,
                realNameEnc,
                idCardNoEnc,
                idCardHash,
                providerBizId,
                /* verifiedAt= */ null,
                /* failedReason= */ null,
                /* failedAt= */ null,
                this.retryCount24h,
                this.createdAt,
                now);
    }

    /**
     * Transition into {@link RealnameStatus#VERIFIED} — terminal per FR-015.
     * Clears failure-trace fields so a previous {@code FAILED → PENDING → VERIFIED}
     * round-trip leaves no stale {@code failedReason / failedAt} on the row.
     */
    public RealnameProfile withVerified(Instant verifiedAt) {
        Objects.requireNonNull(verifiedAt, "verifiedAt must not be null");
        RealnameStateMachine.assertCanTransition(this.status, RealnameStatus.VERIFIED);
        return new RealnameProfile(
                this.id,
                this.accountId,
                RealnameStatus.VERIFIED,
                this.realNameEnc,
                this.idCardNoEnc,
                this.idCardHash,
                this.providerBizId,
                verifiedAt,
                /* failedReason= */ null,
                /* failedAt= */ null,
                this.retryCount24h,
                this.createdAt,
                verifiedAt);
    }

    /**
     * Transition into {@link RealnameStatus#FAILED} with a reason. The 24h
     * retry counter is incremented for every reason <b>except</b>
     * {@link FailedReason#USER_CANCELED} (per FR-009 / SC-005: cancellations
     * are not authentication failures and must not exhaust the user's retry
     * budget).
     */
    public RealnameProfile withFailed(FailedReason reason, Instant failedAt) {
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(failedAt, "failedAt must not be null");
        RealnameStateMachine.assertCanTransition(this.status, RealnameStatus.FAILED);
        int nextRetryCount = (reason == FailedReason.USER_CANCELED) ? this.retryCount24h : this.retryCount24h + 1;
        return new RealnameProfile(
                this.id,
                this.accountId,
                RealnameStatus.FAILED,
                this.realNameEnc,
                this.idCardNoEnc,
                this.idCardHash,
                this.providerBizId,
                /* verifiedAt= */ null,
                reason,
                failedAt,
                nextRetryCount,
                this.createdAt,
                failedAt);
    }
}
