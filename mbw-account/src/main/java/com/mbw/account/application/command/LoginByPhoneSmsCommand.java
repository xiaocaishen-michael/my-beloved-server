package com.mbw.account.application.command;

import java.util.Objects;

/**
 * Input for {@code LoginByPhoneSmsUseCase}.
 *
 * <p>Same E.164 phone format as register-by-phone (FR-001 / FR-002).
 * No password — login by SMS is the password-less path.
 */
public record LoginByPhoneSmsCommand(String phone, String code) {

    public LoginByPhoneSmsCommand {
        Objects.requireNonNull(phone, "phone must not be null");
        Objects.requireNonNull(code, "code must not be null");
    }
}
