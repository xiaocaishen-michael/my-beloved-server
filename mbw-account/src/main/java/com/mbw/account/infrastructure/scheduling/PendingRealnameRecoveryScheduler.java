package com.mbw.account.infrastructure.scheduling;

import com.mbw.account.application.port.QueryVerificationResult;
import com.mbw.account.application.port.RealnameVerificationProvider;
import com.mbw.account.domain.exception.ProviderErrorException;
import com.mbw.account.domain.exception.ProviderTimeoutException;
import com.mbw.account.domain.model.FailedReason;
import com.mbw.account.domain.model.RealnameProfile;
import com.mbw.account.domain.repository.RealnameProfileRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recovery driver for {@code RealnameProfile} rows stuck at {@code PENDING}
 * (realname-verification spec T13b).
 *
 * <p>The {@code InitiateRealnameVerificationUseCase} (T13) commits a PENDING
 * row in Tx1 <em>before</em> calling Aliyun. If the HTTP call returns OK, the
 * client polls {@code GET /verifications/{bizId}} which lands the terminal
 * state via {@code ConfirmRealnameVerificationUseCase} (T14). If the user
 * abandons the flow, the network drops, or BASE B+1 compensation runs but the
 * client never polls, the row sits at PENDING forever — this scheduler reaps
 * those rows by polling Aliyun's {@code queryVerification} every
 * {@link #FIXED_RATE_MS} ms for rows whose {@code updated_at} is older than
 * {@link #STALE_THRESHOLD}.
 *
 * <p>Failure handling:
 * <ul>
 *   <li>{@link ProviderTimeoutException} — swallow, log WARN; the next tick
 *       retries (network blip).</li>
 *   <li>{@link ProviderErrorException} — Aliyun says the bizId is invalid /
 *       expired; mark FAILED + {@link FailedReason#PROVIDER_ERROR} so the row
 *       drops out of the next batch (queryable to user as a permanent failure).</li>
 *   <li>Any other unchecked exception escapes the per-row try block per the
 *       same Checkstyle suppression rationale as
 *       {@code FrozenAccountAnonymizationScheduler}: domain code can't enumerate
 *       every {@code DataAccessException} subtype.</li>
 * </ul>
 */
@Component
public class PendingRealnameRecoveryScheduler {

    static final Duration STALE_THRESHOLD = Duration.ofMinutes(10);
    static final long FIXED_RATE_MS = 5L * 60L * 1000L; // 5 min — compile-time constant for @Scheduled
    static final int LIMIT = 100;

    private static final Logger LOG = LoggerFactory.getLogger(PendingRealnameRecoveryScheduler.class);

    private final RealnameProfileRepository realnameProfileRepository;
    private final RealnameVerificationProvider provider;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public PendingRealnameRecoveryScheduler(
            RealnameProfileRepository realnameProfileRepository,
            RealnameVerificationProvider provider,
            MeterRegistry meterRegistry) {
        this(realnameProfileRepository, provider, meterRegistry, Clock.systemUTC());
    }

    PendingRealnameRecoveryScheduler(
            RealnameProfileRepository realnameProfileRepository,
            RealnameVerificationProvider provider,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.realnameProfileRepository = realnameProfileRepository;
        this.provider = provider;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Scheduled(fixedRate = FIXED_RATE_MS)
    public void recoverStalePendingRows() {
        Instant now = Instant.now(clock);
        Instant threshold = now.minus(STALE_THRESHOLD);
        List<RealnameProfile> stale = realnameProfileRepository.findStalePendingOlderThan(threshold, LIMIT);

        for (RealnameProfile profile : stale) {
            meterRegistry.counter("account.realname.recovery.scanned").increment();
            try {
                QueryVerificationResult result = provider.queryVerification(profile.providerBizId());
                RealnameProfile updated = applyOutcome(profile, result, now);
                realnameProfileRepository.save(updated);
                meterRegistry.counter("account.realname.recovery.recovered").increment();
            } catch (ProviderTimeoutException ex) {
                meterRegistry.counter("account.realname.recovery.timeouts").increment();
                LOG.warn("realname recovery timeout, will retry next tick bizId={}", profile.providerBizId());
            } catch (ProviderErrorException ex) {
                meterRegistry.counter("account.realname.recovery.errors").increment();
                LOG.warn(
                        "realname recovery provider error bizId={} reason={}",
                        profile.providerBizId(),
                        ex.getMessage());
                realnameProfileRepository.save(profile.withFailed(FailedReason.PROVIDER_ERROR, now));
            }
        }
        LOG.info("realname recovery batch done scanned={}", stale.size());
    }

    private static RealnameProfile applyOutcome(RealnameProfile profile, QueryVerificationResult result, Instant now) {
        return switch (result.outcome()) {
            case PASSED -> profile.withVerified(now);
            case NAME_ID_NOT_MATCH -> profile.withFailed(FailedReason.NAME_ID_MISMATCH, now);
            case LIVENESS_FAILED -> profile.withFailed(FailedReason.LIVENESS_FAILED, now);
            case USER_CANCELED -> profile.withFailed(FailedReason.USER_CANCELED, now);
        };
    }
}
