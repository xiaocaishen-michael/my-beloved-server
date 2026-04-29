package com.mbw.account.application.usecase;

import com.mbw.account.application.command.RequestSmsCodeCommand;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.service.PhonePolicy;
import com.mbw.shared.api.sms.SmsClient;
import com.mbw.shared.api.sms.SmsCodeService;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * "Request SMS verification code" use case (FR-006 + FR-012).
 *
 * <p>Pipeline:
 *
 * <ol>
 *   <li>{@code PhonePolicy.validate} — FR-001 E.164 + mainland
 *   <li>3 rate-limit gates (FR-006): {@code sms-60s:<phone>} 1/min,
 *       {@code sms-24h:<phone>} 10/day, {@code sms-ip:<ip>} 50/day.
 *       Distinct keys for distinct windows so each Bucket4j bucket
 *       has its own bandwidth (a single shared key would lose either
 *       the 60s or the 24h limit).
 *   <li>{@code accountRepo.existsByPhone} — branch by registration
 *       state per FR-012:
 *       <ul>
 *         <li>not registered: {@code SmsCodeService.generateAndStore}
 *             returns plaintext, sent via {@code SmsClient} with
 *             Template A (variable: {@code code})
 *         <li>already registered: {@code SmsClient} sends Template B
 *             (no code; the message is "this phone is already
 *             registered, please log in"). Same {@code sms:*}
 *             rate-limit bucket as Template A so wall-clock latency
 *             is indistinguishable to an enumeration attacker
 *       </ul>
 * </ol>
 *
 * <p>Per spec §US-3 AS-1, the HTTP-layer response from this use case
 * is byte-identical for both branches. This class returns nothing;
 * the controller maps it to an empty 200/202.
 */
@Service
public class RequestSmsCodeUseCase {

    static final String SMS_TEMPLATE_REGISTER = "SMS_REGISTER_A";
    static final String SMS_TEMPLATE_ALREADY_REGISTERED = "SMS_REGISTERED_B";

    static final Bandwidth PER_PHONE_60S = Bandwidth.builder()
            .capacity(1)
            .refillIntervally(1, Duration.ofSeconds(60))
            .build();
    static final Bandwidth PER_PHONE_24H = Bandwidth.builder()
            .capacity(10)
            .refillIntervally(10, Duration.ofHours(24))
            .build();
    static final Bandwidth PER_IP_24H = Bandwidth.builder()
            .capacity(50)
            .refillIntervally(50, Duration.ofHours(24))
            .build();

    private final RateLimitService rateLimitService;
    private final AccountRepository accountRepository;
    private final SmsCodeService smsCodeService;
    private final SmsClient smsClient;

    public RequestSmsCodeUseCase(
            RateLimitService rateLimitService,
            AccountRepository accountRepository,
            SmsCodeService smsCodeService,
            SmsClient smsClient) {
        this.rateLimitService = rateLimitService;
        this.accountRepository = accountRepository;
        this.smsCodeService = smsCodeService;
        this.smsClient = smsClient;
    }

    public void execute(RequestSmsCodeCommand cmd) {
        PhoneNumber phone = PhonePolicy.validate(cmd.phone());

        rateLimitService.consumeOrThrow("sms-60s:" + phone.e164(), PER_PHONE_60S);
        rateLimitService.consumeOrThrow("sms-24h:" + phone.e164(), PER_PHONE_24H);
        rateLimitService.consumeOrThrow("sms-ip:" + cmd.clientIp(), PER_IP_24H);

        if (accountRepository.existsByPhone(phone)) {
            // FR-012 alternate template — never reveals the registration
            // boundary via response shape or latency
            smsClient.send(phone.e164(), SMS_TEMPLATE_ALREADY_REGISTERED, Map.of());
        } else {
            String plaintext = smsCodeService.generateAndStore(phone.e164());
            smsClient.send(phone.e164(), SMS_TEMPLATE_REGISTER, Map.of("code", plaintext));
        }
    }
}
