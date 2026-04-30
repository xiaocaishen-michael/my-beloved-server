package com.mbw.shared.api.email;

/**
 * Thrown by {@link EmailSender} implementations when the upstream
 * provider rejects the request or the call cannot be completed (after
 * any provider-side retry).
 *
 * <p>Unchecked so it propagates cleanly through Resilience4j retry
 * decorators without forcing every caller to declare {@code throws}.
 */
public class EmailSendException extends RuntimeException {

    public EmailSendException(String message) {
        super(message);
    }

    public EmailSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
