package com.mbw.account.application.usecase;

import com.mbw.account.application.command.UpdateDisplayNameCommand;
import com.mbw.account.application.result.AccountProfileResult;
import com.mbw.account.domain.exception.AccountInactiveException;
import com.mbw.account.domain.exception.AccountNotFoundException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.DisplayName;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Update display name" write use case ({@code PATCH /api/v1/accounts/me}).
 *
 * <p>Per spec/account/account-profile/spec.md FR-003 / FR-004 / FR-005 /
 * FR-008 / FR-009: validates the raw input via {@link DisplayName}, then
 * mutates the aggregate via {@link AccountStateMachine#changeDisplayName}
 * (single ACTIVE-only entry point).
 *
 * <p>Order of gates: rate-limit first (so spam still consumes the
 * bucket), then VO validation, then auth/state checks. {@link DisplayName}
 * construction throws {@link IllegalArgumentException} prefixed
 * {@code INVALID_DISPLAY_NAME}, mapped by the cross-cutting handler to
 * HTTP 400.
 */
@Service
public class UpdateDisplayNameUseCase {

    static final String RATE_LIMIT_KEY_PREFIX = "me-patch:";

    /** FR-008: 10 attempts per 60s per account; tighter than the GET bucket. */
    static final Bandwidth BANDWIDTH = Bandwidth.builder()
            .capacity(10)
            .refillIntervally(10, Duration.ofSeconds(60))
            .build();

    private final AccountRepository accountRepository;
    private final RateLimitService rateLimitService;

    public UpdateDisplayNameUseCase(AccountRepository accountRepository, RateLimitService rateLimitService) {
        this.accountRepository = accountRepository;
        this.rateLimitService = rateLimitService;
    }

    @Transactional(rollbackFor = Throwable.class)
    public AccountProfileResult execute(UpdateDisplayNameCommand cmd) {
        rateLimitService.consumeOrThrow(RATE_LIMIT_KEY_PREFIX + cmd.accountId().value(), BANDWIDTH);

        DisplayName displayName = new DisplayName(cmd.rawDisplayName());

        Account account = accountRepository.findById(cmd.accountId()).orElseThrow(AccountNotFoundException::new);
        if (account.status() != AccountStatus.ACTIVE) {
            throw new AccountInactiveException();
        }

        AccountStateMachine.changeDisplayName(account, displayName, Instant.now());
        accountRepository.save(account);

        return new AccountProfileResult(
                account.id(), account.phone(), account.displayName(), account.status(), account.createdAt());
    }
}
