package com.mbw.account.application.port;

import com.mbw.account.domain.model.AccountId;
import java.time.Instant;

/**
 * Application-layer port for "what to do to side data when an account
 * is anonymized" (anonymize-frozen-accounts spec CL-002). The use case
 * consumes a {@code List<AnonymizeStrategy>} so adding a new side
 * effect (e.g. {@code ThirdPartyBindingAnonymizeStrategy} in M1.4 once
 * Google / WeChat bindings land) is a register-bean change with zero
 * use-case-layer churn.
 *
 * <p>Implementations live in {@code infrastructure.scheduling}; each
 * is a simple {@code @Component} that delegates to a single
 * repository write inside the surrounding transaction.
 */
public interface AnonymizeStrategy {

    /**
     * Apply this strategy's anonymization step for the given account.
     * Runs inside the use case's REQUIRES_NEW transaction so failures
     * roll back the entire FROZEN → ANONYMIZED transition (per spec
     * FR-007).
     *
     * @param accountId the account being anonymized
     * @param now the anonymize instant; UTC
     */
    void apply(AccountId accountId, Instant now);
}
