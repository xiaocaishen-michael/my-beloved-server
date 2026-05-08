package com.mbw.account.application.usecase;

import com.mbw.account.application.command.InitiateRealnameCommand;
import com.mbw.account.application.port.CipherService;
import com.mbw.account.application.port.InitVerificationRequest;
import com.mbw.account.application.port.InitVerificationResult;
import com.mbw.account.application.port.RealnameVerificationProvider;
import com.mbw.account.application.result.InitiateRealnameResult;
import com.mbw.account.application.service.IdentityHashService;
import com.mbw.account.domain.exception.AccountInFreezePeriodException;
import com.mbw.account.domain.exception.AccountInactiveException;
import com.mbw.account.domain.exception.AccountNotFoundException;
import com.mbw.account.domain.exception.AgreementRequiredException;
import com.mbw.account.domain.exception.AlreadyVerifiedException;
import com.mbw.account.domain.exception.IdCardOccupiedException;
import com.mbw.account.domain.exception.InvalidIdCardFormatException;
import com.mbw.account.domain.exception.ProviderErrorException;
import com.mbw.account.domain.exception.ProviderTimeoutException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.FailedReason;
import com.mbw.account.domain.model.RealnameProfile;
import com.mbw.account.domain.model.RealnameStatus;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.RealnameProfileRepository;
import com.mbw.account.domain.service.IdentityNumberValidator;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Initiate realname verification (BASE B+1 split-tx pattern).
 *
 * <p>Tx1 validates and persists a PENDING row → commit. The Aliyun init call
 * runs <em>outside</em> any tx; on failure, Tx2 marks the row FAILED with
 * PROVIDER_ERROR (per spec drift #3 simplification, agreement persistence is
 * deferred to a future M3 spec — here we only enforce non-blank agreementVersion).
 */
@Service
public class InitiateRealnameVerificationUseCase {

    static final String RATE_LIMIT_ACCOUNT_PREFIX = "realname:account:";
    static final String RATE_LIMIT_IP_PREFIX = "realname:ip:";

    static final Bandwidth PER_ACCOUNT_60S = Bandwidth.builder()
            .capacity(5)
            .refillIntervally(5, Duration.ofSeconds(60))
            .build();

    static final Bandwidth PER_IP_60S = Bandwidth.builder()
            .capacity(20)
            .refillIntervally(20, Duration.ofSeconds(60))
            .build();

    private final TransactionTemplate transactionTemplate;
    private final AccountRepository accountRepository;
    private final RealnameProfileRepository realnameProfileRepository;
    private final IdentityHashService identityHashService;
    private final RateLimitService rateLimitService;
    private final CipherService cipherService;
    private final RealnameVerificationProvider provider;

    public InitiateRealnameVerificationUseCase(
            TransactionTemplate transactionTemplate,
            AccountRepository accountRepository,
            RealnameProfileRepository realnameProfileRepository,
            IdentityHashService identityHashService,
            RateLimitService rateLimitService,
            CipherService cipherService,
            RealnameVerificationProvider provider) {
        this.transactionTemplate = transactionTemplate;
        this.accountRepository = accountRepository;
        this.realnameProfileRepository = realnameProfileRepository;
        this.identityHashService = identityHashService;
        this.rateLimitService = rateLimitService;
        this.cipherService = cipherService;
        this.provider = provider;
    }

    public InitiateRealnameResult execute(InitiateRealnameCommand cmd) {
        PendingPayload pending = transactionTemplate.execute(status -> {
            Account account = accountRepository.findById(cmd.accountId()).orElseThrow(AccountNotFoundException::new);
            if (account.status() == AccountStatus.FROZEN) {
                throw new AccountInFreezePeriodException(account.freezeUntil());
            }
            if (account.status() != AccountStatus.ACTIVE) {
                throw new AccountInactiveException();
            }

            if (cmd.agreementVersion() == null || cmd.agreementVersion().isBlank()) {
                throw new AgreementRequiredException();
            }

            if (!IdentityNumberValidator.validate(cmd.idCardNo())) {
                throw new InvalidIdCardFormatException();
            }

            rateLimitService.consumeOrThrow(
                    RATE_LIMIT_ACCOUNT_PREFIX + cmd.accountId().value(), PER_ACCOUNT_60S);
            rateLimitService.consumeOrThrow(RATE_LIMIT_IP_PREFIX + cmd.clientIp(), PER_IP_60S);

            Optional<RealnameProfile> existingByAccount =
                    realnameProfileRepository.findByAccountId(cmd.accountId().value());
            if (existingByAccount
                    .map(p -> p.status() == RealnameStatus.VERIFIED)
                    .orElse(false)) {
                throw new AlreadyVerifiedException();
            }

            String idCardHash = identityHashService.sha256Hex(cmd.idCardNo());
            Optional<RealnameProfile> sameHash = realnameProfileRepository.findByIdCardHash(idCardHash);
            if (sameHash.isPresent()) {
                RealnameProfile other = sameHash.get();
                boolean crossAccountActive = other.accountId()
                                != cmd.accountId().value()
                        && (other.status() == RealnameStatus.PENDING || other.status() == RealnameStatus.VERIFIED);
                if (crossAccountActive) {
                    throw new IdCardOccupiedException();
                }
            }

            byte[] realNameEnc = cipherService.encrypt(cmd.realName().getBytes(StandardCharsets.UTF_8));
            byte[] idCardNoEnc = cipherService.encrypt(cmd.idCardNo().getBytes(StandardCharsets.UTF_8));
            String providerBizId = UUID.randomUUID().toString();
            Instant now = Instant.now();

            RealnameProfile base = existingByAccount.orElseGet(
                    () -> RealnameProfile.unverified(cmd.accountId().value(), now));
            RealnameProfile pendingProfile = base.withPending(realNameEnc, idCardNoEnc, idCardHash, providerBizId, now);

            try {
                realnameProfileRepository.save(pendingProfile);
            } catch (DataIntegrityViolationException e) {
                throw new IdCardOccupiedException();
            }
            return new PendingPayload(providerBizId, cmd.realName(), cmd.idCardNo());
        });

        try {
            InitVerificationResult result = provider.initVerification(
                    new InitVerificationRequest(pending.providerBizId(), pending.realName(), pending.idCardNo()));
            return new InitiateRealnameResult(pending.providerBizId(), result.livenessUrl());
        } catch (ProviderTimeoutException | ProviderErrorException ex) {
            transactionTemplate.executeWithoutResult(status -> realnameProfileRepository
                    .findByProviderBizId(pending.providerBizId())
                    .ifPresent(p ->
                            realnameProfileRepository.save(p.withFailed(FailedReason.PROVIDER_ERROR, Instant.now()))));
            throw ex;
        }
    }

    private record PendingPayload(String providerBizId, String realName, String idCardNo) {}
}
