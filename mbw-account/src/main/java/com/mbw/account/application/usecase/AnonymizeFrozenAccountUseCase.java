package com.mbw.account.application.usecase;

import com.mbw.account.api.event.AccountAnonymizedEvent;
import com.mbw.account.application.command.AnonymizeFrozenAccountCommand;
import com.mbw.account.application.port.AnonymizeStrategy;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.service.PhoneHasher;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Anonymize a single FROZEN account" use case (anonymize-frozen-accounts
 * spec § Use Case, M1.3). Invoked by
 * {@code FrozenAccountAnonymizationScheduler} once per id returned from
 * the batch scan; runs in its own REQUIRES_NEW transaction so a single
 * row failure does not roll back sibling rows in the same batch
 * (FR-007).
 *
 * <p>Sequence:
 *
 * <ol>
 *   <li>findByIdForUpdate — pessimistic write lock, serialises against
 *       a concurrent cancel-deletion on the same row (SC-007)
 *   <li>Capture {@code phoneHash} from {@code account.phone().e164()}
 *       <em>before</em> the state machine clears phone
 *   <li>{@link AccountStateMachine#markAnonymizedFromFrozen} — pins
 *       display_name to "已注销用户", checks
 *       {@code freezeUntil <= now}, throws otherwise
 *   <li>{@code accountRepo.save} — persists status=ANONYMIZED + null
 *       phone + previous_phone_hash
 *   <li>Each registered {@link AnonymizeStrategy} runs in turn (refresh
 *       tokens revoked, sms_code rows deleted)
 *   <li>Publish {@link AccountAnonymizedEvent} via Spring Modulith
 *       outbox (committed with the same TX)
 * </ol>
 *
 * <p>Any exception rolls everything back (no partial anonymization).
 * Domain rejection (status drift, race-with-cancel) surfaces as
 * {@link IllegalStateException}; the scheduler interprets it as
 * "skip and don't count as failure" per spec FR-005.
 */
@Service
public class AnonymizeFrozenAccountUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(AnonymizeFrozenAccountUseCase.class);

    private final AccountRepository accountRepository;
    private final List<AnonymizeStrategy> strategies;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    public AnonymizeFrozenAccountUseCase(
            AccountRepository accountRepository,
            List<AnonymizeStrategy> strategies,
            ApplicationEventPublisher eventPublisher) {
        this(accountRepository, strategies, eventPublisher, Clock.systemUTC());
    }

    AnonymizeFrozenAccountUseCase(
            AccountRepository accountRepository,
            List<AnonymizeStrategy> strategies,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.strategies = strategies;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRES_NEW)
    public void execute(AnonymizeFrozenAccountCommand cmd) {
        Instant now = Instant.now(clock);
        Account account = accountRepository
                .findByIdForUpdate(cmd.accountId())
                .orElseThrow(() -> new IllegalStateException(
                        "Account not found for anonymize: " + cmd.accountId().value()));

        if (account.phone() == null) {
            // Already-anonymized rows should never be picked by
            // findFrozenWithExpiredGracePeriod (status filter rejects);
            // surfacing as ISE means a bug elsewhere, not a routine race.
            throw new IllegalStateException("Account already has null phone, cannot rehash: "
                    + cmd.accountId().value());
        }

        String phoneHash = PhoneHasher.sha256Hex(account.phone().e164());
        AccountStateMachine.markAnonymizedFromFrozen(account, now, phoneHash);
        accountRepository.save(account);

        for (AnonymizeStrategy strategy : strategies) {
            strategy.apply(cmd.accountId(), now);
        }

        eventPublisher.publishEvent(new AccountAnonymizedEvent(cmd.accountId(), now, now));

        LOG.info("account.anonymized accountId={}", cmd.accountId().value());
    }
}
