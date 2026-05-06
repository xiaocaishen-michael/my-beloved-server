package com.mbw.account.application.command;

import com.mbw.account.domain.model.AccountId;
import java.util.Objects;

/**
 * Command for {@code AnonymizeFrozenAccountUseCase} (anonymize-frozen-accounts
 * spec M1.3). Produced by the scheduler from
 * {@code AccountRepository.findFrozenWithExpiredGracePeriod}; the use
 * case loads the row under pessimistic lock and runs the FROZEN →
 * ANONYMIZED transition in its own REQUIRES_NEW transaction.
 */
public record AnonymizeFrozenAccountCommand(AccountId accountId) {

    public AnonymizeFrozenAccountCommand {
        Objects.requireNonNull(accountId, "accountId must not be null");
    }
}
