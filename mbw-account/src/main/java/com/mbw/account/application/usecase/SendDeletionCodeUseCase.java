package com.mbw.account.application.usecase;

import com.mbw.account.application.command.SendDeletionCodeCommand;
import com.mbw.account.domain.exception.AccountInactiveException;
import com.mbw.account.domain.exception.AccountNotFoundException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountSmsCode;
import com.mbw.account.domain.model.AccountSmsCodePurpose;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.AccountSmsCodeRepository;
import com.mbw.shared.api.sms.SmsClient;
import com.mbw.shared.api.sms.SmsCodePlaintextGenerator;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * "Send deletion verification code" use case (delete-account spec
 * Endpoint 1, M1.3 T7).
 *
 * <ol>
 *   <li>Account + IP rate-limit gates
 *   <li>Load account + validate status == ACTIVE
 *   <li>Generate 6-digit code via {@link SecureRandom} + SHA-256 hex
 *   <li>Persist {@link AccountSmsCode} (purpose=DELETE_ACCOUNT)
 *   <li>Send SMS to account's registered phone
 * </ol>
 */
@Service
public class SendDeletionCodeUseCase {

    static final String SMS_TEMPLATE = "SMS_DELETE_CODE";
    static final Duration CODE_TTL = Duration.ofMinutes(10);

    static final Bandwidth PER_ACCOUNT_60S = Bandwidth.builder()
            .capacity(1)
            .refillIntervally(1, Duration.ofSeconds(60))
            .build();
    static final Bandwidth PER_IP_60S = Bandwidth.builder()
            .capacity(5)
            .refillIntervally(5, Duration.ofSeconds(60))
            .build();

    private static final Logger LOG = LoggerFactory.getLogger(SendDeletionCodeUseCase.class);
    private static final HexFormat HEX = HexFormat.of();

    private final RateLimitService rateLimitService;
    private final AccountRepository accountRepository;
    private final AccountSmsCodeRepository smsCodeRepository;
    private final SmsClient smsClient;
    private final SmsCodePlaintextGenerator codeGenerator;
    private final Clock clock;

    @Autowired
    public SendDeletionCodeUseCase(
            RateLimitService rateLimitService,
            AccountRepository accountRepository,
            AccountSmsCodeRepository smsCodeRepository,
            SmsClient smsClient,
            SmsCodePlaintextGenerator codeGenerator) {
        this(rateLimitService, accountRepository, smsCodeRepository, smsClient, codeGenerator, Clock.systemUTC());
    }

    SendDeletionCodeUseCase(
            RateLimitService rateLimitService,
            AccountRepository accountRepository,
            AccountSmsCodeRepository smsCodeRepository,
            SmsClient smsClient,
            SmsCodePlaintextGenerator codeGenerator,
            Clock clock) {
        this.rateLimitService = rateLimitService;
        this.accountRepository = accountRepository;
        this.smsCodeRepository = smsCodeRepository;
        this.smsClient = smsClient;
        this.codeGenerator = codeGenerator;
        this.clock = clock;
    }

    public void execute(SendDeletionCodeCommand cmd) {
        rateLimitService.consumeOrThrow("delete-code:account:" + cmd.accountId().value(), PER_ACCOUNT_60S);
        rateLimitService.consumeOrThrow("delete-code:ip:" + cmd.clientIp(), PER_IP_60S);

        Account account = accountRepository.findById(cmd.accountId()).orElseThrow(AccountNotFoundException::new);

        if (account.status() != AccountStatus.ACTIVE) {
            throw new AccountInactiveException();
        }

        Instant now = Instant.now(clock);
        String plaintext = codeGenerator.generateSixDigit();
        String codeHash = sha256Hex(plaintext);
        Instant expiresAt = now.plus(CODE_TTL);

        smsCodeRepository.save(
                AccountSmsCode.create(cmd.accountId(), codeHash, expiresAt, AccountSmsCodePurpose.DELETE_ACCOUNT, now));

        smsClient.send(account.phone().e164(), SMS_TEMPLATE, Map.of("code", plaintext));

        LOG.info("account.deletion-code.sent accountId={}", cmd.accountId().value());
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
