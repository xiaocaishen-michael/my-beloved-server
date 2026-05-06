package com.mbw.account.infrastructure.scheduling;

import com.mbw.account.application.port.AnonymizeStrategy;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.repository.AccountSmsCodeRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Anonymize-side effect: hard-delete every sms_code row for the
 * account (anonymize-frozen-accounts spec FR-004 + CL-003). markUsed
 * retains rows for audit; anonymization sheds the audit trail because
 * the codes are correlated to the (now scrubbed) phone.
 */
@Component
public class SmsCodeAnonymizeStrategy implements AnonymizeStrategy {

    private final AccountSmsCodeRepository smsCodeRepository;

    public SmsCodeAnonymizeStrategy(AccountSmsCodeRepository smsCodeRepository) {
        this.smsCodeRepository = smsCodeRepository;
    }

    @Override
    public void apply(AccountId accountId, Instant now) {
        smsCodeRepository.deleteAllByAccountId(accountId);
    }
}
