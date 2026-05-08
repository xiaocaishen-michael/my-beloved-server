package com.mbw.account.application.usecase;

import com.mbw.account.application.command.ConfirmRealnameCommand;
import com.mbw.account.application.port.QueryVerificationResult;
import com.mbw.account.application.port.RealnameVerificationProvider;
import com.mbw.account.application.result.ConfirmRealnameResult;
import com.mbw.account.domain.exception.RealnameProfileAccessDeniedException;
import com.mbw.account.domain.exception.RealnameProfileNotFoundException;
import com.mbw.account.domain.model.FailedReason;
import com.mbw.account.domain.model.RealnameProfile;
import com.mbw.account.domain.model.RealnameStatus;
import com.mbw.account.domain.repository.RealnameProfileRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Confirm-realname-verification poll use case (realname-verification spec T14,
 * plan.md § ConfirmRealnameVerificationUseCase).
 *
 * <p>Idempotent on terminal states: if the row is already {@code VERIFIED} or
 * {@code FAILED}, returns the persisted snapshot without calling Aliyun (FR-007).
 * Only {@code PENDING} rows hit {@code provider.queryVerification(...)}; the
 * outcome mapping mirrors {@code PendingRealnameRecoveryScheduler} (T13b).
 *
 * <p>Provider timeouts propagate as-is — the row stays {@code PENDING} and the
 * client (or T13b scheduler) will retry on the next poll. Provider biz errors
 * also propagate; T13b is the safety-net that flips the row to FAILED if the
 * client gives up.
 */
@Service
public class ConfirmRealnameVerificationUseCase {

    private final RealnameProfileRepository realnameProfileRepository;
    private final RealnameVerificationProvider provider;
    private final Clock clock;

    @Autowired
    public ConfirmRealnameVerificationUseCase(
            RealnameProfileRepository realnameProfileRepository, RealnameVerificationProvider provider) {
        this(realnameProfileRepository, provider, Clock.systemUTC());
    }

    ConfirmRealnameVerificationUseCase(
            RealnameProfileRepository realnameProfileRepository, RealnameVerificationProvider provider, Clock clock) {
        this.realnameProfileRepository = realnameProfileRepository;
        this.provider = provider;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Throwable.class)
    public ConfirmRealnameResult execute(ConfirmRealnameCommand cmd) {
        RealnameProfile profile = realnameProfileRepository
                .findByProviderBizId(cmd.providerBizId())
                .orElseThrow(RealnameProfileNotFoundException::new);

        if (profile.accountId() != cmd.callerAccountId().value()) {
            throw new RealnameProfileAccessDeniedException();
        }

        if (profile.status() == RealnameStatus.VERIFIED || profile.status() == RealnameStatus.FAILED) {
            return toResult(profile);
        }

        QueryVerificationResult queryResult = provider.queryVerification(cmd.providerBizId());
        Instant now = Instant.now(clock);
        RealnameProfile updated = applyOutcome(profile, queryResult, now);
        RealnameProfile saved = realnameProfileRepository.save(updated);
        return toResult(saved);
    }

    private static RealnameProfile applyOutcome(RealnameProfile profile, QueryVerificationResult result, Instant now) {
        return switch (result.outcome()) {
            case PASSED -> profile.withVerified(now);
            case NAME_ID_NOT_MATCH -> profile.withFailed(FailedReason.NAME_ID_MISMATCH, now);
            case LIVENESS_FAILED -> profile.withFailed(FailedReason.LIVENESS_FAILED, now);
            case USER_CANCELED -> profile.withFailed(FailedReason.USER_CANCELED, now);
        };
    }

    private static ConfirmRealnameResult toResult(RealnameProfile profile) {
        return new ConfirmRealnameResult(
                profile.providerBizId(), profile.status(), profile.failedReason(), profile.verifiedAt());
    }
}
