package com.mbw.account.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Hashes a phone number into a SHA-256 lowercase hex string for
 * persistence on anonymized accounts (anonymize-frozen-accounts spec
 * FR-003 / V10 migration).
 *
 * <p>Mirrors {@link RefreshTokenHasher} — pure domain service, zero
 * Spring dependency, all behaviour exposed via static methods. The hash
 * is intentionally unsalted: the use case (fraud-detection lookup
 * "has this phone been anonymized?") needs a deterministic mapping
 * from phone → hash, so a per-row salt would defeat the purpose. The
 * acceptable consequence is documented in spec.md Out of Scope —
 * anyone who knows a phone can verify whether it has been anonymized.
 */
public final class PhoneHasher {

    private static final HexFormat HEX = HexFormat.of();

    private PhoneHasher() {}

    /**
     * @param phone raw phone (E.164, e.g. {@code "+8613800138000"});
     *     UTF-8 bytes are fed to SHA-256
     * @return 64-character lowercase hex string
     * @throws NullPointerException if {@code phone} is null
     * @throws IllegalArgumentException if {@code phone} is blank
     */
    public static String sha256Hex(String phone) {
        Objects.requireNonNull(phone, "phone must not be null");
        if (phone.isBlank()) {
            throw new IllegalArgumentException("phone must not be blank");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(phone.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java SE distribution; mirror
            // RefreshTokenHasher's escape hatch.
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
