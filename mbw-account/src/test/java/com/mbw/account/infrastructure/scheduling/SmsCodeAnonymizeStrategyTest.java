package com.mbw.account.infrastructure.scheduling;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.repository.AccountSmsCodeRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SmsCodeAnonymizeStrategyTest {

    @Test
    void apply_should_route_to_deleteAllByAccountId() {
        AccountSmsCodeRepository repo = mock(AccountSmsCodeRepository.class);
        SmsCodeAnonymizeStrategy strategy = new SmsCodeAnonymizeStrategy(repo);
        AccountId accountId = new AccountId(42L);
        Instant now = Instant.parse("2026-05-21T03:00:00Z");

        strategy.apply(accountId, now);

        verify(repo).deleteAllByAccountId(accountId);
    }
}
