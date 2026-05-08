package com.mbw.account.application.result;

import com.mbw.account.domain.model.FailedReason;
import com.mbw.account.domain.model.RealnameStatus;
import java.time.Instant;

/**
 * Use-case-level projection of an account's realname-verification status
 * (realname-verification spec T12 / plan.md § QueryRealnameStatusUseCase).
 *
 * <p>Field nullability by status:
 *
 * <ul>
 *   <li>{@code UNVERIFIED} — all sensitive fields null
 *   <li>{@code PENDING} — all sensitive fields null (still in upstream flow)
 *   <li>{@code VERIFIED} — {@code realNameMasked} / {@code idCardMasked} /
 *       {@code verifiedAt} populated; {@code failedReason} null
 *   <li>{@code FAILED} — {@code failedReason} populated; sensitive fields null
 * </ul>
 *
 * <p>{@code realNameMasked} / {@code idCardMasked} are the {@code FR-008}
 * masked forms (last-char preserved for name; first+last preserved for ID
 * card) — never plaintext. Masking is applied in the use case after cipher
 * decrypt via {@link com.mbw.account.domain.model.RealnameProfile#maskRealName}
 * / {@link com.mbw.account.domain.model.RealnameProfile#maskIdCardNo}.
 */
public record RealnameStatusResult(
        RealnameStatus status,
        String realNameMasked,
        String idCardMasked,
        Instant verifiedAt,
        FailedReason failedReason) {

    public static RealnameStatusResult unverified() {
        return new RealnameStatusResult(RealnameStatus.UNVERIFIED, null, null, null, null);
    }

    public static RealnameStatusResult pending() {
        return new RealnameStatusResult(RealnameStatus.PENDING, null, null, null, null);
    }

    public static RealnameStatusResult failed(FailedReason reason) {
        return new RealnameStatusResult(RealnameStatus.FAILED, null, null, null, reason);
    }

    public static RealnameStatusResult verified(String realNameMasked, String idCardMasked, Instant verifiedAt) {
        return new RealnameStatusResult(RealnameStatus.VERIFIED, realNameMasked, idCardMasked, verifiedAt, null);
    }
}
