package com.mbw.shared.api.sms;

/**
 * Thrown by {@link SmsClient} implementations when the upstream SMS
 * gateway rejects the request or the call cannot be completed.
 *
 * <p>Unchecked so it propagates cleanly through Resilience4j retry /
 * circuit-breaker decorators without forcing every caller to declare
 * {@code throws}. Application code maps this to a domain error code
 * (typically {@code SMS_SEND_FAILED}); see
 * {@code spec/account/register-by-phone/spec.md} FR-006.
 */
public class SmsSendException extends RuntimeException {

    public SmsSendException(String message) {
        super(message);
    }

    public SmsSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
