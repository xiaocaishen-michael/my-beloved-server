package com.mbw.account.application.command;

import java.util.Objects;
import java.util.Optional;

/**
 * Input for {@code RegisterByPhoneUseCase}.
 *
 * <p>{@code password} is optional per FR-003 — when absent, the
 * resulting account has only a phone credential and login subsequent
 * sessions go via {@code login-by-phone-sms}.
 */
public record RegisterByPhoneCommand(String phone, String code, Optional<String> password) {

    public RegisterByPhoneCommand {
        Objects.requireNonNull(phone, "phone must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(password, "password Optional must not be null (use Optional.empty())");
    }
}
