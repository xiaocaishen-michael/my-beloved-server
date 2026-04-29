package com.mbw.account.application.command;

import java.util.Objects;

/**
 * Input for {@code RequestSmsCodeUseCase}: the phone the user wants a
 * verification code for, plus the client IP for FR-006 IP-tier
 * rate-limiting.
 */
public record RequestSmsCodeCommand(String phone, String clientIp) {

    public RequestSmsCodeCommand {
        Objects.requireNonNull(phone, "phone must not be null");
        Objects.requireNonNull(clientIp, "clientIp must not be null");
    }
}
