package com.mbw.app.infrastructure.email;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resend HTTPS API client configuration (ADR-0013 second amendment).
 *
 * <p>Bound from {@code mbw.email.resend.*}. Auto-scanned by
 * {@code @ConfigurationPropertiesScan} on {@code MbwApplication}, so
 * binding happens unconditionally — even when {@code mbw.email.provider}
 * is unset (dev/test). For that reason fields are <b>not</b>
 * {@code @NotBlank}-validated here; if the values are missing, the
 * gated {@link ResendEmailClient} bean is not created at all (and the
 * fallback {@link LoggingEmailSender} steps in). Validation lives at
 * the consumer-side ctor in {@link ResendEmailClient}.
 *
 * <p>Mirror of {@link com.mbw.app.infrastructure.sms.AliyunSmsProperties}
 * / {@link com.mbw.app.infrastructure.sms.MockSmsProperties} — keep
 * dev/test boots clean while still rejecting a misconfigured production
 * startup.
 */
@ConfigurationProperties(prefix = "mbw.email.resend")
public record ResendProperties(String apiKey, String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public ResendProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.resend.com";
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(10);
        }
    }
}
