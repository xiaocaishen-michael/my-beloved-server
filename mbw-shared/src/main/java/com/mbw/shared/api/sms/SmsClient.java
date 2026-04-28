package com.mbw.shared.api.sms;

import java.util.Map;

/**
 * Cross-module SMS gateway abstraction.
 *
 * <p>Business modules ({@code mbw-account}, future {@code mbw-iam}, etc.)
 * depend on this interface to send templated SMS without coupling to a
 * specific provider. The concrete implementation
 * ({@code AliyunSmsClient}) lives in {@code mbw-app/infrastructure/sms}
 * so the deployment unit owns provider integration while
 * {@code mbw-shared} stays free of infrastructure concerns. See
 * {@code spec/account/register-by-phone/plan.md} § "SmsCodeService 跨模块归属".
 *
 * <p>Implementations should treat upstream failures (network errors,
 * quota exceeded, malformed templates) as {@link SmsSendException} —
 * callers map this to a domain-level error code (typically
 * {@code SMS_SEND_FAILED} → HTTP 502 / 503).
 */
public interface SmsClient {

    /**
     * Send a templated SMS to the given phone number.
     *
     * @param phone E.164-formatted phone number (e.g. {@code +8613800138000})
     * @param templateId provider-specific template identifier
     *     (e.g. Aliyun {@code SMS_xxxxxxx})
     * @param params template variable substitutions; keys must match
     *     placeholders defined in the registered template
     * @throws SmsSendException when the upstream gateway rejects the
     *     request or the call cannot be completed (after retries)
     */
    void send(String phone, String templateId, Map<String, String> params);
}
