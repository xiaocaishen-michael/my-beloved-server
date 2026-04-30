package com.mbw.shared.api.email;

import java.util.Objects;

/**
 * Immutable transactional email payload.
 *
 * <p>{@code text} is plaintext body; HTML body and attachments are out
 * of scope for the M1 use cases (SMS-via-email mock per ADR-0013) — add
 * an overload only when a real use case lands.
 *
 * <p>Validation is {@link Objects#requireNonNull} on construction; we
 * deliberately do not pull in {@code jakarta.validation} from the
 * {@code shared} module to keep the cross-module contract lightweight.
 */
public record EmailMessage(String from, String to, String subject, String text) {

    public EmailMessage {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(text, "text");
        if (from.isBlank() || to.isBlank() || subject.isBlank() || text.isBlank()) {
            throw new IllegalArgumentException("EmailMessage fields must not be blank");
        }
    }
}
