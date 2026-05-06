package com.mbw.account.infrastructure.scheduling;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefreshTokenAnonymizeStrategyTest {

    @Test
    void apply_should_route_to_revokeAllForAccount() {
        RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
        RefreshTokenAnonymizeStrategy strategy = new RefreshTokenAnonymizeStrategy(repo);
        AccountId accountId = new AccountId(42L);
        Instant now = Instant.parse("2026-05-21T03:00:00Z");

        strategy.apply(accountId, now);

        verify(repo).revokeAllForAccount(accountId, now);
    }
}
