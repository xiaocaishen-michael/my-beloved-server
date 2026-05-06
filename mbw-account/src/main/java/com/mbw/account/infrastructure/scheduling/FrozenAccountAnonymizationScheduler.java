package com.mbw.account.infrastructure.scheduling;

import com.mbw.account.application.command.AnonymizeFrozenAccountCommand;
import com.mbw.account.application.usecase.AnonymizeFrozenAccountUseCase;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.repository.AccountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron-driven driver for the FROZEN → ANONYMIZED batch
 * (anonymize-frozen-accounts spec FR-001 / FR-002 / FR-007 / FR-009).
 *
 * <p>Once a day at 03:00 Asia/Shanghai (matches
 * {@code spring.task.scheduling.timezone}), scans up to {@link #LIMIT}
 * FROZEN account ids whose {@code freeze_until} has elapsed, and
 * dispatches each into {@link AnonymizeFrozenAccountUseCase}'s
 * REQUIRES_NEW transaction. Per-row failures do not stop the loop;
 * domain rejections (status drift races with cancel-deletion) are
 * silenced as DEBUG, while genuine errors increment a Micrometer
 * counter and are logged WARN, escalating to ERROR after the same
 * account fails {@link #PERSISTENT_FAILURE_THRESHOLD} cron ticks in
 * a row (in-memory tally; M1 single-instance assumption).
 *
 * <p>Metrics emitted under the {@code account.anonymize.*} namespace
 * (counters: {@code scanned}, {@code succeeded}, {@code failures},
 * {@code persistent_failures}; timer: {@code batch_duration}). The
 * Prometheus scrape lives at {@code /actuator/prometheus}.
 */
@Component
public class FrozenAccountAnonymizationScheduler {

    static final int LIMIT = 100;
    static final int PERSISTENT_FAILURE_THRESHOLD = 3;

    private static final Logger LOG = LoggerFactory.getLogger(FrozenAccountAnonymizationScheduler.class);

    private final AccountRepository accountRepository;
    private final AnonymizeFrozenAccountUseCase useCase;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    private final Map<AccountId, Integer> failureCounts = new ConcurrentHashMap<>();

    @Autowired
    public FrozenAccountAnonymizationScheduler(
            AccountRepository accountRepository, AnonymizeFrozenAccountUseCase useCase, MeterRegistry meterRegistry) {
        this(accountRepository, useCase, meterRegistry, Clock.systemUTC());
    }

    FrozenAccountAnonymizationScheduler(
            AccountRepository accountRepository,
            AnonymizeFrozenAccountUseCase useCase,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.useCase = useCase;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")
    public void anonymizeExpiredFrozenAccounts() {
        Timer.Sample sample = Timer.start(meterRegistry);
        Instant now = Instant.now(clock);
        List<AccountId> candidates = accountRepository.findFrozenWithExpiredGracePeriod(now, LIMIT);

        int succeeded = 0;
        int failed = 0;
        for (AccountId accountId : candidates) {
            meterRegistry.counter("account.anonymize.scanned").increment();
            try {
                useCase.execute(new AnonymizeFrozenAccountCommand(accountId));
                meterRegistry.counter("account.anonymize.succeeded").increment();
                failureCounts.remove(accountId);
                succeeded++;
            } catch (IllegalStateException | OptimisticLockingFailureException skipped) {
                // Domain rejection (status drift, race with cancel-deletion) —
                // not a failure per FR-005; the row is no longer eligible.
                LOG.debug(
                        "anonymize skipped accountId={} reason={}",
                        accountId.value(),
                        skipped.getClass().getSimpleName());
            } catch (RuntimeException ex) {
                // Catches anything else: Spring DataAccessException
                // subtypes (DB blip), Resilience / circuit failures, OOM /
                // bookkeeping bugs in unrelated components. Listing each
                // subtype individually would drift as Spring / dependencies
                // evolve and miss new failure modes — the scheduler must
                // keep the batch loop running across all of them. Suppression
                // entry: config/checkstyle/checkstyle-suppressions.xml.
                meterRegistry
                        .counter(
                                "account.anonymize.failures",
                                "reason",
                                ex.getClass().getSimpleName())
                        .increment();
                int count = failureCounts.merge(accountId, 1, Integer::sum);
                if (count >= PERSISTENT_FAILURE_THRESHOLD) {
                    LOG.error("anonymize persistent failure accountId={} count={}", accountId.value(), count, ex);
                    meterRegistry
                            .counter("account.anonymize.persistent_failures")
                            .increment();
                } else {
                    LOG.warn("anonymize failure accountId={} count={}", accountId.value(), count, ex);
                }
                failed++;
            }
        }
        sample.stop(meterRegistry.timer("account.anonymize.batch_duration"));
        LOG.info("anonymize batch done scanned={} succeeded={} failed={}", candidates.size(), succeeded, failed);
    }
}
