package com.mbw.account.domain.service;

import com.mbw.account.domain.model.RefreshTokenHash;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes a raw refresh token (the 256-bit base64url string the client
 * holds) into a {@link RefreshTokenHash} for server-side storage.
 *
 * <p>SHA-256 in lowercase hex via {@link HexFormat#of()}. No salt, no
 * pepper, no key — refresh tokens are themselves 256-bit high-entropy
 * random values, so the hash is purely a one-way mapping for storage
 * (a DB leak yields hashes; an attacker still cannot pre-image to a
 * usable raw token).
 *
 * <p>Wrapped in a domain service (not inlined into UseCase) so M2+ can
 * swap to keyed HMAC via this single seam if a downstream threat
 * assessment requires it (per spec.md CL-001 落点).
 */
public final class RefreshTokenHasher {

    private static final HexFormat HEX = HexFormat.of();

    private RefreshTokenHasher() {}

    public static RefreshTokenHash hash(String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return new RefreshTokenHash(HEX.formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java SE distribution; if it
            // somehow disappears we have far bigger problems than refresh
            // tokens. Surface as ISE so the JVM startup tests fail loudly.
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
