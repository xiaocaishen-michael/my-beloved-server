package com.mbw.account.application.port;

/**
 * Application-layer port for symmetric encryption of realname PII at rest
 * (realname-verification spec T6 / plan D-002).
 *
 * <p>Two implementations live in {@code infrastructure.security}:
 *
 * <ul>
 *   <li>{@code EnvDekCipherService} (M1) — AES-GCM with a base64-encoded DEK
 *       loaded from {@code MBW_REALNAME_DEK_BASE64}.
 *   <li>{@code AliyunKmsCipherService} (M3 stub, throws on call) — KMS
 *       envelope encryption; activated by profile, plug-and-play with no
 *       use-case churn.
 * </ul>
 *
 * <p>The interface intentionally exposes raw {@code byte[]} so neither
 * algorithm parameters nor IV layout leak into application / web code.
 * Implementations must include a unique IV per {@code encrypt} invocation
 * (FR-006) and surface tamper / key-mismatch as runtime exceptions —
 * never return cleartext for malformed ciphertext.
 */
public interface CipherService {

    /**
     * Encrypt {@code plaintext} into self-contained ciphertext (IV + payload
     * + auth tag concatenated). Repeated calls on the same plaintext must
     * produce different output (random IV per call).
     */
    byte[] encrypt(byte[] plaintext);

    /**
     * Decrypt ciphertext produced by {@link #encrypt}. Throws (rather than
     * returning a partial / empty result) when the auth tag fails or the
     * ciphertext was produced under a different key.
     */
    byte[] decrypt(byte[] ciphertext);
}
