package com.mbw.account.application.command;

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
 *   <li>已注册 + FROZEN/ANONYMIZED → 反枚举吞为 INVALID_CREDENTIALS
 * </ul>
 *
 * <p>{@code clientIp} feeds the {@code auth:<ip>} rate-limit bucket
 * (per spec phone-sms-auth FR-007).
 */
public record PhoneSmsAuthCommand(String phone, String code, String clientIp) {

    public PhoneSmsAuthCommand {
        Objects.requireNonNull(phone, "phone must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(clientIp, "clientIp must not be null");
    }
}
