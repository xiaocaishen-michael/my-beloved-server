package com.mbw.shared.api.sms;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Centralised 6-digit SMS verification code plaintext generator.
 *
 * <p>Used by every SMS-driven flow (login / register / delete-account /
 * cancel-deletion / future …) to keep code generation policy in one place.
 *
 * <p>The {@code mbw.sms.dev-fixed-code} property is a dev-only escape hatch:
 * set the env {@code MBW_SMS_DEV_FIXED_CODE} to a 6-digit literal (e.g.
 * {@code 999999}) and {@link #generateSixDigit()} returns that value
 * verbatim instead of a random one. {@link com.mbw.app.infrastructure.sms.LoggingSmsClient}
 * stubs out real SMS so the dev / Playwright user has no other way to obtain
 * a working code without injecting a Redis hash by hand. Production
 * (`docker-compose.tight.yml` does not pass the env) goes through the random
 * branch.
 *
 * <p>Validation: env value must match {@code \d{6}} exactly. Anything else
 * (blank, 5 digits, contains letters, …) silently falls through to random
 * generation — defence in depth against a misconfigured prod env.
 */
@Component
public class SmsCodePlaintextGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(SmsCodePlaintextGenerator.class);
    private static final int CODE_DIGITS = 6;
    private static final int CODE_BOUND = 1_000_000;
    private static final String FIXED_CODE_PATTERN = "\\d{" + CODE_DIGITS + "}";

    private final SecureRandom random;
    private final String devFixedCode;

    // @Autowired explicit on the primary constructor — Spring 不能自动选 between
    // this and the package-private test constructor below (multi-ctor bean
    // wiring rule, per the same fix applied to SmsClient/EmailClient in PR #84).
    @Autowired
    public SmsCodePlaintextGenerator(@Value("${mbw.sms.dev-fixed-code:}") String devFixedCode) {
        this(devFixedCode, new SecureRandom());
    }

    SmsCodePlaintextGenerator(String devFixedCode, SecureRandom random) {
        this.devFixedCode = devFixedCode;
        this.random = Objects.requireNonNull(random);
    }

    /** Returns a 6-digit code; respects {@code MBW_SMS_DEV_FIXED_CODE} env. */
    public String generateSixDigit() {
        if (devFixedCode != null && devFixedCode.matches(FIXED_CODE_PATTERN)) {
            LOG.warn("[dev-fixed-code] using fixed SMS code from env (NOT for prod)");
            return devFixedCode;
        }
        return String.format(Locale.ROOT, "%0" + CODE_DIGITS + "d", random.nextInt(CODE_BOUND));
    }
}
