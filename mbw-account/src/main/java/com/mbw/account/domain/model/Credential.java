package com.mbw.account.domain.model;

/**
 * Sealed root for the credential type hierarchy (CL-001 in
 * {@code specs/auth/register-by-phone/spec.md}).
 *
 * <p>An ACTIVE account always has a {@link PhoneCredential} and may
 * optionally have a {@link PasswordCredential} (FR-003 makes the
 * password optional at registration). The sealed hierarchy lets the
 * compiler enforce exhaustive handling in pattern-matching switches —
 * adding a third credential type (e.g. WeChatOpenIdCredential in M1.3)
 * forces every consumer to either handle it or explicitly default,
 * preventing silent drift.
 *
 * <p>Per CLAUDE.md § 二, credential persistence is owned by the
 * {@code mbw-account.infrastructure.persistence} layer; this domain
 * type carries no JPA / Spring annotations.
 */
public sealed interface Credential permits PhoneCredential, PasswordCredential {

    AccountId account();
}
