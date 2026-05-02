package com.mbw.account.application.usecase;

import com.mbw.account.application.command.RequestSmsCodeCommand;
import com.mbw.account.application.command.SmsCodePurpose;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.service.PhonePolicy;
import com.mbw.account.domain.service.TimingDefenseExecutor;
import com.mbw.shared.api.sms.SmsClient;
import com.mbw.shared.api.sms.SmsCodeService;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * "Request SMS verification code" use case (FR-006 / FR-009 / FR-012).
 *
 * <p>Pipeline:
 *
 * <ol>
 *   <li>{@code PhonePolicy.validate} — FR-001 E.164 + mainland
 *   <li>3 rate-limit gates (FR-006): {@code sms-60s:<phone>} 1/min,
 *       {@code sms-24h:<phone>} 10/day, {@code sms-ip:<ip>} 50/day.
 *   <li>{@code accountRepo.existsByPhone} + {@link SmsCodePurpose}
 *       dispatch per FR-009:
 *       <ul>
 *         <li>REGISTER + unregistered → Template A (real code)
 *         <li>REGISTER + registered → Template B (already registered)
 *         <li>LOGIN + registered → Template A (real code, login flow)
 *         <li>LOGIN + unregistered + Template C approved → Template C
 *             (login on unregistered, advise register)
 *         <li>LOGIN + unregistered + Template C unavailable → no SMS
 *             sent, but wall-clock padded via
 *             {@link TimingDefenseExecutor} so an enumeration attacker
 *             cannot detect the absence
 *       </ul>
 * </ol>
 *
 * <p>Per spec §US-3 AS-1, the HTTP-layer response from this use case
 * is byte-identical for all branches. This class returns nothing; the
 * controller maps it to an empty 200/202.
 */
@Service
public class RequestSmsCodeUseCase {

    static final String SMS_TEMPLATE_REGISTER = "SMS_REGISTER_A";
    static final String SMS_TEMPLATE_ALREADY_REGISTERED = "SMS_REGISTERED_B";

    /**
     * Target wall-clock duration for the LOGIN+unregistered+Template-C-
     * unavailable fallback path. Calibrated to typical Aliyun SMS
     * gateway latency (~150ms) so the absence of a real send is not
     * detectable by a timing observer.
     */
    static final Duration TIMING_TARGET_FALLBACK = Duration.ofMillis(150);

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

    /**
     * Aliyun template id for the "login on unregistered phone" message
     * (Template C). Empty string means the template has not yet been
     * approved by Aliyun; the LOGIN+unregistered branch then runs the
     * fallback (no SMS + pad time). Configured via
     * {@code mbw.sms.template.login-unregistered}.
     */
    private final String smsTemplateLoginUnregistered;

    public RequestSmsCodeUseCase(
            RateLimitService rateLimitService,
            AccountRepository accountRepository,
            SmsCodeService smsCodeService,
            SmsClient smsClient,
            @Value("${mbw.sms.template.login-unregistered:}") String smsTemplateLoginUnregistered) {
        this.rateLimitService = rateLimitService;
        this.accountRepository = accountRepository;
        this.smsCodeService = smsCodeService;
        this.smsClient = smsClient;
        this.smsTemplateLoginUnregistered = smsTemplateLoginUnregistered == null ? "" : smsTemplateLoginUnregistered;
    }

    public void execute(RequestSmsCodeCommand cmd) {
        PhoneNumber phone = PhonePolicy.validate(cmd.phone());

        rateLimitService.consumeOrThrow("sms-60s:" + phone.e164(), PER_PHONE_60S);
        rateLimitService.consumeOrThrow("sms-24h:" + phone.e164(), PER_PHONE_24H);
        rateLimitService.consumeOrThrow("sms-ip:" + cmd.clientIp(), PER_IP_24H);

        boolean registered = accountRepository.existsByPhone(phone);
        switch (cmd.purpose()) {
            case REGISTER -> dispatchRegister(phone, registered);
            case LOGIN -> dispatchLogin(phone, registered);
            default -> throw new IllegalStateException("unhandled purpose: " + cmd.purpose());
        }
    }

    private void dispatchRegister(PhoneNumber phone, boolean registered) {
        if (registered) {
            // FR-012 alternate template — never reveals registration boundary
            smsClient.send(phone.e164(), SMS_TEMPLATE_ALREADY_REGISTERED, Map.of());
        } else {
            String plaintext = smsCodeService.generateAndStore(phone.e164());
            smsClient.send(phone.e164(), SMS_TEMPLATE_REGISTER, Map.of("code", plaintext));
        }
    }

    private void dispatchLogin(PhoneNumber phone, boolean registered) {
        if (registered) {
            // LOGIN + registered: same Template A as register-unregistered;
            // generate + send a real code so the user can complete login
            String plaintext = smsCodeService.generateAndStore(phone.e164());
            smsClient.send(phone.e164(), SMS_TEMPLATE_REGISTER, Map.of("code", plaintext));
        } else if (templateCAvailable()) {
            // Template C approved: send the "login attempted on unregistered"
            // notice so the user knows to register first
            smsClient.send(phone.e164(), smsTemplateLoginUnregistered, Map.of());
        } else {
            // Template C not yet approved (per ADR-0013 / Assumption A-006):
            // skip the SMS but pad the wall clock so the absence is invisible
            // to a timing-side-channel observer
            TimingDefenseExecutor.executeInConstantTime(TIMING_TARGET_FALLBACK, () -> null);
        }
    }

    private boolean templateCAvailable() {
        return !smsTemplateLoginUnregistered.isBlank();
    }
}
