package com.mbw.account.web.request;

import com.mbw.account.application.command.LoginByPasswordCommand;
import jakarta.validation.constraints.NotBlank;

/**
 * HTTP body for {@code POST /api/v1/auth/login-by-password}.
 *
 * <p>Phone format and password strength validation run in domain
 * ({@code PhonePolicy}, BCrypt verify); the web layer's
 * {@code @NotBlank} only catches the absent / empty cases — so Spring
 * throws a clean {@code MethodArgumentNotValidException} before the
 * use case spends a BCrypt cost-8 on them.
 *
 * <p>Note (FR-006): login deliberately does NOT validate password
 * strength — strength is only enforced at register-time. Submitting a
 * weak password just hashes to a value that doesn't match → 401.
 */
public record LoginByPasswordRequest(@NotBlank String phone, @NotBlank String password) {

    /**
     * Build the application-layer command, attaching the request's
     * client IP for the {@code auth:&lt;ip&gt;} rate-limit bucket.
     */
    public LoginByPasswordCommand toCommand(String clientIp) {
        return new LoginByPasswordCommand(phone, password, clientIp);
    }
}
