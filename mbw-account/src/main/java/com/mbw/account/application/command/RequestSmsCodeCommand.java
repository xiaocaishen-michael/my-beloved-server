package com.mbw.account.application.command;

import java.util.Objects;

/**
 * Input for {@code RequestSmsCodeUseCase}: the phone the user wants a
 * verification code for, plus the client IP for FR-007 IP-tier
 * rate-limiting.
 *
 * <p>Per ADR-0016 + spec {@code phone-sms-auth/spec.md} FR-004: the
 * {@code purpose} field has been removed — server sends a single
 * Template A real verification code regardless of phone existence
 * (反枚举一致响应).
 */
public record RequestSmsCodeCommand(String phone, String clientIp) {

    public RequestSmsCodeCommand {
        Objects.requireNonNull(phone, "phone must not be null");
        Objects.requireNonNull(clientIp, "clientIp must not be null");
    }
}
