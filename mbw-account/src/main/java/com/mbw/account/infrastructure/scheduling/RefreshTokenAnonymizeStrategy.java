package com.mbw.account.infrastructure.scheduling;

import com.mbw.account.application.port.AnonymizeStrategy;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Anonymize-side effect: bulk-revoke every refresh_token row for the
 * account (anonymize-frozen-accounts spec FR-004). Reuses the
 * {@code revokeAllForAccount} method introduced for logout-all; the
 * partial index {@code idx_refresh_token_account_id_active} keeps the
 * write cheap.
 */
@Component
public class RefreshTokenAnonymizeStrategy implements AnonymizeStrategy {

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenAnonymizeStrategy(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Override
    public void apply(AccountId accountId, Instant now) {
        refreshTokenRepository.revokeAllForAccount(accountId, now);
    }
}
