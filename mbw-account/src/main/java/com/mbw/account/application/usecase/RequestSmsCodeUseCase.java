package com.mbw.account.application.usecase;

import com.mbw.account.application.command.RequestSmsCodeCommand;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.service.PhonePolicy;
import com.mbw.shared.api.sms.SmsClient;
import com.mbw.shared.api.sms.SmsCodeService;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * "Request SMS verification code" use case (per ADR-0016 unified
 * mobile-first phone-SMS auth + spec
 * {@code phone-sms-auth/spec.md} FR-004).
 *
 * <p>Simplified from the prior 4-branch dispatcher (REGISTER + LOGIN ×
 * registered + unregistered → Templates A / B / C). Under unified
 * auth, the client has no concept of register vs login — the server
 * sends a single Template A real verification code regardless of
 * phone existence. This collapses to a single code path with
 * byte-identical response (FR-006 反枚举一致响应):
 *
 * <ol>
 *   <li>{@link PhonePolicy#validate} (FR-002 E.164 + mainland)
 *   <li>3 rate-limit gates (FR-007): {@code sms-60s:<phone>} 1/min,
 *       {@code sms-24h:<phone>} 10/day, {@code sms-ip:<ip>} 50/day
 *   <li>Generate code via {@link SmsCodeService} + send Template A
 * </ol>
 *
 * <p>Note: Template B (registered → "already registered" notice) +
 * Template C (login on unregistered → "register first" notice) are
 * no longer needed — the new {@code UnifiedPhoneSmsAuthUseCase}
 * handles unregistered phones by auto-creating accounts, so users on
 * unregistered phones land in success rather than getting a notice.
 */
@Service
public class RequestSmsCodeUseCase {

    static final String SMS_TEMPLATE = "SMS_REGISTER_A";

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
    private final SmsCodeService smsCodeService;
    private final SmsClient smsClient;

    public RequestSmsCodeUseCase(
            RateLimitService rateLimitService, SmsCodeService smsCodeService, SmsClient smsClient) {
        this.rateLimitService = rateLimitService;
        this.smsCodeService = smsCodeService;
        this.smsClient = smsClient;
    }

    public void execute(RequestSmsCodeCommand cmd) {
        PhoneNumber phone = PhonePolicy.validate(cmd.phone());

        rateLimitService.consumeOrThrow("sms-60s:" + phone.e164(), PER_PHONE_60S);
        rateLimitService.consumeOrThrow("sms-24h:" + phone.e164(), PER_PHONE_24H);
        rateLimitService.consumeOrThrow("sms-ip:" + cmd.clientIp(), PER_IP_24H);

        String plaintext = smsCodeService.generateAndStore(phone.e164());
        smsClient.send(phone.e164(), SMS_TEMPLATE, Map.of("code", plaintext));
    }
}
