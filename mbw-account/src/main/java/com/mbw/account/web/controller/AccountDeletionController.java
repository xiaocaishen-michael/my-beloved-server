package com.mbw.account.web.controller;

import com.mbw.account.application.command.DeleteAccountCommand;
import com.mbw.account.application.command.SendDeletionCodeCommand;
import com.mbw.account.application.usecase.DeleteAccountUseCase;
import com.mbw.account.application.usecase.SendDeletionCodeUseCase;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.web.exception.MissingAuthenticationException;
import com.mbw.account.web.request.DeleteAccountRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for the delete-account use case (per
 * {@code spec/account/delete-account/spec.md}).
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code POST /api/v1/accounts/me/deletion-codes} — trigger SMS
 *       verification code; requires ACTIVE account + valid Bearer JWT.
 *   <li>{@code POST /api/v1/accounts/me/deletion} — submit 6-digit code
 *       to confirm deletion; transitions account ACTIVE → FROZEN, revokes
 *       all sessions, and publishes {@code AccountDeletionRequestedEvent}.
 * </ul>
 *
 * <p>Both endpoints require a valid Bearer JWT stashed as the
 * {@code mbw.accountId} request attribute by {@code JwtAuthFilter}; a
 * missing or wrong-type attribute throws {@link MissingAuthenticationException}
 * → 401 AUTH_FAILED (byte-equal to AccountNotFound / AccountInactive,
 * per anti-enumeration in {@code AccountWebExceptionAdvice}).
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountDeletionController {

    private final SendDeletionCodeUseCase sendDeletionCodeUseCase;
    private final DeleteAccountUseCase deleteAccountUseCase;

    public AccountDeletionController(
            SendDeletionCodeUseCase sendDeletionCodeUseCase, DeleteAccountUseCase deleteAccountUseCase) {
        this.sendDeletionCodeUseCase = sendDeletionCodeUseCase;
        this.deleteAccountUseCase = deleteAccountUseCase;
    }

    @PostMapping("/me/deletion-codes")
    public ResponseEntity<Void> sendCode(HttpServletRequest request) {
        AccountId accountId = authenticatedAccountId(request);
        sendDeletionCodeUseCase.execute(new SendDeletionCodeCommand(accountId, clientIp(request)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/deletion")
    public ResponseEntity<Void> delete(@Valid @RequestBody DeleteAccountRequest body, HttpServletRequest request) {
        AccountId accountId = authenticatedAccountId(request);
        deleteAccountUseCase.execute(new DeleteAccountCommand(accountId, body.code(), clientIp(request)));
        return ResponseEntity.noContent().build();
    }

    /**
     * Read the {@code mbw.accountId} request attribute populated by the
     * Bearer-JWT filter. Missing / wrong-type → {@link MissingAuthenticationException}
     * → 401 (anti-enum, mapped uniformly with AccountNotFound / AccountInactive
     * in {@code AccountWebExceptionAdvice}).
     */
    private static AccountId authenticatedAccountId(HttpServletRequest request) {
        Object attr = request.getAttribute("mbw.accountId");
        if (attr instanceof AccountId accountId) {
            return accountId;
        }
        throw new MissingAuthenticationException();
    }

    /**
     * Resolve client IP for IP-tier rate-limiting. Honours the first
     * {@code X-Forwarded-For} entry when behind Nginx (per ADR-0002);
     * falls back to remote addr. Anti-spoofing of XFF is the reverse
     * proxy's responsibility.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
