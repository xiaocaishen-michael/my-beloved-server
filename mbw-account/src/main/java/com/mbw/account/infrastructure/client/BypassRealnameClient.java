package com.mbw.account.infrastructure.client;

import com.mbw.account.application.port.InitVerificationRequest;
import com.mbw.account.application.port.InitVerificationResult;
import com.mbw.account.application.port.QueryVerificationResult;
import com.mbw.account.application.port.QueryVerificationResult.Outcome;
import com.mbw.account.application.port.RealnameVerificationProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Dev / staging bypass implementation of {@link RealnameVerificationProvider}
 * (realname-verification spec T9).
 *
 * <p>Activated by {@code mbw.realname.dev-bypass=true}; intentionally not
 * the default — production must run with {@code AliyunRealnameClient}.
 * T11 wires in a fail-fast on prod profile to prevent this client from
 * being selected outside dev / staging.
 *
 * <p>Behavior driven entirely by {@code MBW_REALNAME_DEV_FIXED_RESULT}:
 *
 * <ul>
 *   <li>{@code verified} (or unset) — {@link #initVerification} returns
 *       {@code bypass://verified}; {@link #queryVerification} returns
 *       {@link Outcome#PASSED}.
 *   <li>{@code failed} — {@code bypass://failed} +
 *       {@link Outcome#NAME_ID_NOT_MATCH}, used by client devs to
 *       exercise the failure UX without contacting the upstream provider.
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "mbw.realname.dev-bypass", havingValue = "true")
@EnableConfigurationProperties(RealnameDevBypassProperties.class)
public class BypassRealnameClient implements RealnameVerificationProvider {

    private static final String FIXED_VERIFIED = "verified";
    private static final String FIXED_FAILED = "failed";

    private final String fixedResult;

    public BypassRealnameClient(RealnameDevBypassProperties properties) {
        String configured = properties.devFixedResult();
        this.fixedResult = (configured == null || configured.isBlank())
                ? FIXED_VERIFIED
                : configured.trim().toLowerCase();
    }

    @Override
    public InitVerificationResult initVerification(InitVerificationRequest request) {
        return new InitVerificationResult("bypass://" + fixedResult);
    }

    @Override
    public QueryVerificationResult queryVerification(String providerBizId) {
        if (FIXED_FAILED.equals(fixedResult)) {
            return new QueryVerificationResult(Outcome.NAME_ID_NOT_MATCH, "bypass-mode failure injection");
        }
        return new QueryVerificationResult(Outcome.PASSED, null);
    }
}
