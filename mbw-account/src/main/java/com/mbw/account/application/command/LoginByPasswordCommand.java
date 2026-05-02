package com.mbw.account.application.command;

import java.util.Objects;

/**
 * Input for {@code LoginByPasswordUseCase}.
 *
 * <p>{@code clientIp} carried separately for the {@code auth:&lt;ip&gt;}
 * rate-limit bucket (FR-007) — distinct from the {@code login:&lt;phone&gt;}
 * bucket which is shared with login-by-phone-sms.
 */
public record LoginByPasswordCommand(String phone, String password, String clientIp) {

    public LoginByPasswordCommand {
        Objects.requireNonNull(phone, "phone must not be null");
        Objects.requireNonNull(password, "password must not be null");
        Objects.requireNonNull(clientIp, "clientIp must not be null");
    }
}
