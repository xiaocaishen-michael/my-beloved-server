package com.mbw.account.application.service;

import com.mbw.account.application.config.RealnamePepperProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Hashes a plaintext ID card number into a salted SHA-256 hex string for
 * cross-account uniqueness lookups (realname-verification spec FR-013 +
 * V12 migration {@code uk_realname_profile_id_card_hash}).
 *
 * <p>{@code hash = sha256(idCardNo || pepper)} — pepper from
 * {@code MBW_REALNAME_PEPPER}. The pepper makes pre-computed rainbow
 * tables for the 18-digit ID card space useless without DB access; the
 * function is still deterministic across calls for the same input so
 * the unique-index lookup at {@link com.mbw.account.domain.repository.RealnameProfileRepository#findByIdCardHash}
 * works as designed.
 *
 * <p>Lives in {@code application.service} (not {@code domain.service})
 * because the pepper is sourced from infrastructure-level config; domain
 * stays framework-free.
 */
@Service
@EnableConfigurationProperties(RealnamePepperProperties.class)
public class IdentityHashService {

    private static final String ALGORITHM = "SHA-256";
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] pepper;

    public IdentityHashService(RealnamePepperProperties properties) {
        if (properties.value() == null || properties.value().isBlank()) {
            throw new IllegalStateException("mbw.realname.pepper.value is required (set MBW_REALNAME_PEPPER env var)");
        }
        this.pepper = properties.value().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param idCardNo plaintext 18-digit ID card; UTF-8 bytes are concatenated
     *     with the pepper before hashing
     * @return 64-character lowercase hex string
     */
    public String sha256Hex(String idCardNo) {
        Objects.requireNonNull(idCardNo, "idCardNo must not be null");
        if (idCardNo.isBlank()) {
            throw new IllegalArgumentException("idCardNo must not be blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            digest.update(idCardNo.getBytes(StandardCharsets.UTF_8));
            digest.update(pepper);
            return HEX.formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }
}
