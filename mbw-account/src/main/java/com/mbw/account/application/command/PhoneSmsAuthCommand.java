package com.mbw.account.application.command;

import com.mbw.account.web.resolver.DeviceMetadata;
import java.util.Objects;

/**
 * Input for {@code UnifiedPhoneSmsAuthUseCase} (per ADR-0016 unified
 * mobile-first phone-SMS auth).
 *
 * <p>Client-facing single endpoint: phone + 6-digit SMS code. Server
 * auto-branches on phone existence:
 *
 * <ul>
 *   <li>未注册 → 自动创建 ACTIVE Account + sign tokens + return
 *   <li>已注册 + ACTIVE → updateLastLoginAt + sign tokens + return
 *   <li>已注册 + FROZEN → AccountInFreezePeriodException (HTTP 403,
 *       explicit disclosure per spec D expose-frozen-account-status)
 *   <li>已注册 + ANONYMIZED → 反枚举吞为 INVALID_CREDENTIALS
 * </ul>
 *
 * <p>{@code clientIp} feeds the {@code auth:<ip>} rate-limit bucket
 * (per spec phone-sms-auth FR-007).
 *
 * <p>{@code deviceMetadata} carries the X-Device-* header triplet
 * extracted by the controller (device-management spec FR-008 / FR-009);
 * may be {@code null} for callers that have not yet been wired (the
 * UseCase synthesizes a fallback in that branch so legacy tests keep
 * passing).
 */
public record PhoneSmsAuthCommand(String phone, String code, String clientIp, DeviceMetadata deviceMetadata) {

    public PhoneSmsAuthCommand {
        Objects.requireNonNull(phone, "phone must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(clientIp, "clientIp must not be null");
    }

    /** Backward-compat overload — synthesises a fallback metadata triplet. */
    public PhoneSmsAuthCommand(String phone, String code, String clientIp) {
        this(phone, code, clientIp, /* deviceMetadata */ null);
    }
}
