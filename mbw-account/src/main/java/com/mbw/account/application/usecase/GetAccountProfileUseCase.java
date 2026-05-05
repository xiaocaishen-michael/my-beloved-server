package com.mbw.account.application.usecase;

import com.mbw.account.application.result.AccountProfileResult;
import com.mbw.account.domain.exception.AccountInactiveException;
import com.mbw.account.domain.exception.AccountNotFoundException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Get account profile" read use case ({@code GET /api/v1/accounts/me}).
 *
 * <p>Per spec/account/account-profile/spec.md FR-001 / FR-002 / FR-008
 * / FR-009: returns the caller's profile (id / displayName / status /
 * createdAt) or throws to a 401 path on missing-token / unknown account
 * / non-ACTIVE status (anti-enumeration via byte-equal 401 responses,
 * mapped at the web layer).
 *
 * <p>Read-only; defaults to {@code @Transactional(readOnly = true)} so
 * the JPA session is short-lived.
 */
@Service
public class GetAccountProfileUseCase {

    static final String RATE_LIMIT_KEY_PREFIX = "me-get:";

    /** FR-008: 60 attempts per 60s per account; protects read-side scrape. */
    static final Bandwidth BANDWIDTH = Bandwidth.builder()
            .capacity(60)
            .refillIntervally(60, Duration.ofSeconds(60))
            .build();

    private final AccountRepository accountRepository;
    private final RateLimitService rateLimitService;

    public GetAccountProfileUseCase(AccountRepository accountRepository, RateLimitService rateLimitService) {
        this.accountRepository = accountRepository;
        this.rateLimitService = rateLimitService;
    }

    @Transactional(readOnly = true)
    public AccountProfileResult execute(AccountId accountId) {
        rateLimitService.consumeOrThrow(RATE_LIMIT_KEY_PREFIX + accountId.value(), BANDWIDTH);

        Account account = accountRepository.findById(accountId).orElseThrow(AccountNotFoundException::new);
        if (account.status() != AccountStatus.ACTIVE) {
            throw new AccountInactiveException();
        }
        return new AccountProfileResult(account.id(), account.displayName(), account.status(), account.createdAt());
    }
}
