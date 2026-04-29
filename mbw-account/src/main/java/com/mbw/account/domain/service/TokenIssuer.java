package com.mbw.account.domain.service;

import com.mbw.account.domain.model.AccountId;

/**
 * Domain-side abstraction for issuing post-authentication tokens
 * (FR-008).
 *
 * <p>{@link #signAccess} returns a JWT carrying the account id as
 * {@code sub}; clients send it on protected requests.
 * {@link #signRefresh} returns an opaque 256-bit random string; the
 * server stores its hash and rotates a fresh one on every refresh.
 *
 * <p>The split (signed JWT vs opaque random) is per spec FR-008:
 * access tokens are stateless / verifiable client-side; refresh tokens
 * are revocable (server holds the canonical state) and don't need to
 * be JWT.
 */
public interface TokenIssuer {

    /** @return signed JWT, TTL 15min, claim {@code sub=accountId} */
    String signAccess(AccountId accountId);

    /** @return URL-safe base64-encoded 256-bit random opaque token */
    String signRefresh();
}
