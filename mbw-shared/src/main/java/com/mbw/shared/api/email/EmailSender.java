package com.mbw.shared.api.email;

/**
 * Cross-module transactional email gateway abstraction.
 *
 * <p>Callers depend on this interface to send a single email without
 * coupling to a specific provider. The concrete implementation
 * ({@code ResendEmailClient}) lives in {@code mbw-app/infrastructure/email}
 * so the deployment unit owns provider integration while
 * {@code mbw-shared} stays free of infrastructure concerns.
 *
 * <p>Implementations should treat upstream failures (network errors,
 * 5xx, rate-limit) as {@link EmailSendException} after exhausting
 * provider-side retry. Permanent failures (malformed payload, auth
 * rejection) should fail-fast without retry — the caller can then
 * surface a deterministic error code.
 */
public interface EmailSender {

    /**
     * Send a single transactional email.
     *
     * @param message non-null payload (from / to / subject / text)
     * @throws EmailSendException when the upstream gateway rejects the
     *     request or the call cannot be completed (after retries)
     */
    void send(EmailMessage message);
}
