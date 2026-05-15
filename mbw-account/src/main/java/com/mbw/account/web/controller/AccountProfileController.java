package com.mbw.account.web.controller;

import com.mbw.account.application.command.UpdateDisplayNameCommand;
import com.mbw.account.application.usecase.GetAccountProfileUseCase;
import com.mbw.account.application.usecase.UpdateDisplayNameUseCase;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.web.exception.MissingAuthenticationException;
import com.mbw.account.web.request.UpdateDisplayNameRequest;
import com.mbw.account.web.response.AccountProfileResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for the account-profile use case (per
 * {@code specs/account/profile/spec.md}).
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/v1/accounts/me} — return the caller's profile;
 *       null {@code displayName} signals onboarding incomplete.
 *   <li>{@code PATCH /api/v1/accounts/me} — submit a new displayName
 *       (onboarding completion).
 * </ul>
 *
 * <p>Both require a valid Bearer JWT; the {@code @AuthenticatedAccountId}
 * resolver throws {@code MissingAuthenticationException} → 401 when the
 * filter has not stashed an {@code AccountId} on the request. Application
 * layer further folds {@code AccountNotFoundException} +
 * {@code AccountInactiveException} into byte-equal 401 responses
 * (anti-enumeration FR-002 / FR-009).
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountProfileController {

    private final GetAccountProfileUseCase getProfileUseCase;
    private final UpdateDisplayNameUseCase updateDisplayNameUseCase;

    public AccountProfileController(
            GetAccountProfileUseCase getProfileUseCase, UpdateDisplayNameUseCase updateDisplayNameUseCase) {
        this.getProfileUseCase = getProfileUseCase;
        this.updateDisplayNameUseCase = updateDisplayNameUseCase;
    }

    @GetMapping("/me")
    public ResponseEntity<AccountProfileResponse> getMe(HttpServletRequest request) {
        AccountId accountId = authenticatedAccountId(request);
        return ResponseEntity.ok(AccountProfileResponse.from(getProfileUseCase.execute(accountId)));
    }

    @PatchMapping("/me")
    public ResponseEntity<AccountProfileResponse> patchMe(
            HttpServletRequest request, @Valid @RequestBody UpdateDisplayNameRequest body) {
        AccountId accountId = authenticatedAccountId(request);
        return ResponseEntity.ok(AccountProfileResponse.from(
                updateDisplayNameUseCase.execute(new UpdateDisplayNameCommand(accountId, body.displayName()))));
    }

    /**
     * Read the {@code mbw.accountId} request attribute populated by the
     * Bearer-JWT filter. Missing/wrong-type → {@link MissingAuthenticationException}
     * → 401 (anti-enum, mapped uniformly with AccountNotFound /
     * AccountInactive in {@code AccountWebExceptionAdvice}).
     */
    private static AccountId authenticatedAccountId(HttpServletRequest request) {
        Object attr = request.getAttribute("mbw.accountId");
        if (attr instanceof AccountId accountId) {
            return accountId;
        }
        throw new MissingAuthenticationException();
    }
}
