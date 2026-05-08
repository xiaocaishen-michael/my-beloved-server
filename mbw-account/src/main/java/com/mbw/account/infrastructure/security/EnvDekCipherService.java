package com.mbw.account.infrastructure.security;

import com.mbw.account.application.port.CipherService;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * AES-GCM-256 implementation of {@link CipherService} backed by an
 * environment-loaded data encryption key (realname-verification spec T8).
 *
 * <p>Activated by {@code mbw.realname.cipher=env-dek} (also the default
 * via {@code matchIfMissing=true}); the alternative is the
 * {@code AliyunKmsCipherService} stub reserved for M3.
 *
 * <p>Ciphertext layout produced by {@link #encrypt}:
 * <pre>{@code
 *   [ IV (12 bytes) ][ AES-GCM payload (plaintext.length + 16-byte auth tag) ]
 * }</pre>
 *
 * <p>The 12-byte IV is generated per-call via {@link SecureRandom} so that
 * encrypting the same plaintext twice yields distinct ciphertext (FR-006).
 * {@link #decrypt} rejects tampered IV / payload / auth-tag with
 * {@link IllegalStateException} — never returns partial cleartext.
 */
@Service
@ConditionalOnProperty(name = "mbw.realname.cipher", havingValue = "env-dek", matchIfMissing = true)
@EnableConfigurationProperties(RealnameDekProperties.class)
public class EnvDekCipherService implements CipherService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int EXPECTED_DEK_BYTES = 32;

    private final SecretKey aesKey;
    private final SecureRandom random = new SecureRandom();

    public EnvDekCipherService(RealnameDekProperties properties) {
        this.aesKey = parseKey(properties.base64());
    }

    @Override
    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] payload = cipher.doFinal(plaintext);
            byte[] out = new byte[IV_LENGTH_BYTES + payload.length];
            System.arraycopy(iv, 0, out, 0, IV_LENGTH_BYTES);
            System.arraycopy(payload, 0, out, IV_LENGTH_BYTES, payload.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length < IV_LENGTH_BYTES + (TAG_LENGTH_BITS / 8)) {
            throw new IllegalStateException("ciphertext too short to contain IV + auth tag");
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(ciphertext, 0, iv, 0, IV_LENGTH_BYTES);
            byte[] payload = new byte[ciphertext.length - IV_LENGTH_BYTES];
            System.arraycopy(ciphertext, IV_LENGTH_BYTES, payload, 0, payload.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return cipher.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decryption failed (auth tag mismatch or wrong key)", e);
        }
    }

    private static SecretKey parseKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(
                    "mbw.realname.dek.base64 is required (set MBW_REALNAME_DEK_BASE64 env var)");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("mbw.realname.dek.base64 is not valid base64", e);
        }
        if (decoded.length != EXPECTED_DEK_BYTES) {
            throw new IllegalStateException("mbw.realname.dek.base64 must decode to "
                    + EXPECTED_DEK_BYTES
                    + " bytes (AES-256), got "
                    + decoded.length);
        }
        return new SecretKeySpec(decoded, "AES");
    }
}
