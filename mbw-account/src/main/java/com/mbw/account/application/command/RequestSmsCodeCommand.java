package com.mbw.account.application.command;

import java.util.Objects;

/**
 * Input for {@code RequestSmsCodeUseCase}: the phone the user wants a
 * verification code for, plus the client IP for FR-006 IP-tier
 * rate-limiting and the {@link SmsCodePurpose} for FR-009 template
 * dispatch.
 */
public record RequestSmsCodeCommand(String phone, String clientIp, SmsCodePurpose purpose) {

    public RequestSmsCodeCommand {
        Objects.requireNonNull(phone, "phone must not be null");
        Objects.requireNonNull(clientIp, "clientIp must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
    }

    /**
     * Backward-compat overload defaulting to {@link SmsCodePurpose#REGISTER}.
     * Pre-Phase-1.1 callers (and tests) used the 2-arg form before
     * login-by-phone-sms introduced LOGIN as a second purpose.
     */
    public RequestSmsCodeCommand(String phone, String clientIp) {
        this(phone, clientIp, SmsCodePurpose.REGISTER);
    }
}
