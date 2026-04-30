package com.mbw.app.infrastructure.email;

import com.mbw.shared.api.email.EmailMessage;
import com.mbw.shared.api.email.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fallback {@link EmailSender} that only logs — no upstream call.
 *
 * <p>Activation ({@code @ConditionalOnProperty}):
 *
 * <ul>
 *   <li>{@code mbw.email.provider} explicitly set to {@code log}, or
 *   <li>{@code mbw.email.provider} unset ({@code matchIfMissing=true})
 * </ul>
 *
 * <p>Mirrors the {@link com.mbw.app.infrastructure.sms.LoggingSmsClient}
 * pattern: the two candidate {@code EmailSender} impls are mutually
 * exclusive via explicit {@code havingValue}, avoiding the
 * {@code @ConditionalOnMissingBean} race condition.
 *
 * <p>Typical use:
 *
 * <ul>
 *   <li>Local dev where booting Spring is enough but you don't want a
 *     real Resend API key
 *   <li>CI integration tests that exercise upstream paths but do not
 *     verify email delivery
 * </ul>
 *
 * <p>Logs only the {@code from / to / subject} — body text may carry
 * verification codes (CLAUDE.md § 四 log-safety: never log secrets /
 * tokens / verification codes).
 */
@Component
@ConditionalOnProperty(prefix = "mbw.email", name = "provider", havingValue = "log", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(EmailMessage message) {
        LOG.info("[stub-email] would-send from={} to={} subject={}", message.from(), message.to(), message.subject());
    }
}
