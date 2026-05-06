package com.mbw.account.application.usecase;

import com.mbw.account.application.command.SendCancelDeletionCodeCommand;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountSmsCode;
import com.mbw.account.domain.model.AccountSmsCodePurpose;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.AccountSmsCodeRepository;
import com.mbw.account.domain.service.PhonePolicy;
import com.mbw.shared.api.sms.SmsClient;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * "Send cancel-deletion verification code" use case (cancel-deletion
 * spec § Endpoint 1, M1.3).
 *
 * <p>Public, unauthed entry point — clients in FROZEN state have no
 * tokens. Anti-enumeration (FR-006 / SC-002): the four phone classes
 * <em>not eligible</em> for cancel (not registered, ACTIVE, ANONYMIZED,
 * or FROZEN with grace expired) all return 200 silently with no SMS
 * dispatched and no {@code account_sms_code} row written. Only
 * FROZEN-in-grace triggers a real send. A dummy SHA-256 keeps timing
 * indistinguishable across the four ineligible branches.
 *
 * <ol>
 *   <li>{@link PhonePolicy#validate} (E.164 mainland)
 *   <li>Phone-hash + IP rate-limit gates
 *   <li>{@link AccountRepository#findByPhone} → 4-class branch
 *   <li>FROZEN-in-grace: persist code (purpose=CANCEL_DELETION) + SMS
 *   <li>otherwise: dummy hash + return (no persist, no SMS)
 * </ol>
 */
@Service
public class SendCancelDeletionCodeUseCase {

    static final String SMS_TEMPLATE = "SMS_CANCEL_DELETION";
    static final Duration CODE_TTL = Duration.ofMinutes(10);

    static final Bandwidth PER_PHONE_60S = Bandwidth.builder()
            .capacity(1)
            .refillIntervally(1, Duration.ofSeconds(60))
            .build();
    static final Bandwidth PER_IP_60S = Bandwidth.builder()
            .capacity(5)
            .refillIntervally(5, Duration.ofSeconds(60))
            .build();

    private static final Logger LOG = LoggerFactory.getLogger(SendCancelDeletionCodeUseCase.class);
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RateLimitService rateLimitService;
    private final AccountRepository accountRepository;
    private final AccountSmsCodeRepository smsCodeRepository;
    private final SmsClient smsClient;
    private final Clock clock;

    @Autowired
    public SendCancelDeletionCodeUseCase(
            RateLimitService rateLimitService,
            AccountRepository accountRepository,
            AccountSmsCodeRepository smsCodeRepository,
            SmsClient smsClient) {
        this(rateLimitService, accountRepository, smsCodeRepository, smsClient, Clock.systemUTC());
    }

    SendCancelDeletionCodeUseCase(
            RateLimitService rateLimitService,
            AccountRepository accountRepository,
            AccountSmsCodeRepository smsCodeRepository,
            SmsClient smsClient,
            Clock clock) {
        this.rateLimitService = rateLimitService;
        this.accountRepository = accountRepository;
        this.smsCodeRepository = smsCodeRepository;
        this.smsClient = smsClient;
        this.clock = clock;
    }

    public void execute(SendCancelDeletionCodeCommand cmd) {
        PhoneNumber phone = PhonePolicy.validate(cmd.phone());
        String phoneHash = sha256Hex(phone.e164());

        rateLimitService.consumeOrThrow("cancel-code:phone:" + phoneHash, PER_PHONE_60S);
        rateLimitService.consumeOrThrow("cancel-code:ip:" + cmd.clientIp(), PER_IP_60S);

        Optional<Account> maybeAccount = accountRepository.findByPhone(phone);
        Instant now = Instant.now(clock);

        boolean eligible = maybeAccount
                .map(a -> a.status() == AccountStatus.FROZEN
                        && a.freezeUntil() != null
                        && a.freezeUntil().isAfter(now))
                .orElse(false);

        if (!eligible) {
            // Dummy SHA-256 to keep timing indistinguishable across the four ineligible
            // branches (per FR-006 timing defense).
            sha256Hex("cancel-deletion-dummy:" + phoneHash);
            LOG.info("account.cancel-deletion-code.attempted phoneHash={} eligible=false", phoneHash);
            return;
        }

        Account account = maybeAccount.get();
        String plaintext = generateCode();
        String codeHash = sha256Hex(plaintext);
        Instant expiresAt = now.plus(CODE_TTL);

        smsCodeRepository.save(
                AccountSmsCode.create(account.id(), codeHash, expiresAt, AccountSmsCodePurpose.CANCEL_DELETION, now));

        smsClient.send(account.phone().e164(), SMS_TEMPLATE, Map.of("code", plaintext));

        LOG.info("account.cancel-deletion-code.sent phoneHash={}", phoneHash);
    }

    private static String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
