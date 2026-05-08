package com.mbw.account.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class EnvDekCipherServiceTest {

    private static final byte[] PLAINTEXT = "11010119900101001X".getBytes(StandardCharsets.UTF_8);

    private final EnvDekCipherService cipher = new EnvDekCipherService(validProperties());

    @Test
    void encrypt_then_decrypt_should_round_trip_to_original_plaintext() {
        byte[] ciphertext = cipher.encrypt(PLAINTEXT);
        byte[] roundTripped = cipher.decrypt(ciphertext);

        assertThat(roundTripped).isEqualTo(PLAINTEXT);
    }

    @Test
    void encrypt_should_produce_distinct_ciphertext_for_same_plaintext_due_to_random_iv() {
        byte[] a = cipher.encrypt(PLAINTEXT);
        byte[] b = cipher.encrypt(PLAINTEXT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void decrypt_should_throw_when_ciphertext_is_tampered() {
        byte[] ciphertext = cipher.encrypt(PLAINTEXT);
        // Flip one byte in the payload region (after the 12-byte IV)
        ciphertext[ciphertext.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(ciphertext)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decrypt_should_throw_when_iv_is_tampered() {
        byte[] ciphertext = cipher.encrypt(PLAINTEXT);
        // Flip one byte in the IV region (first 12 bytes)
        ciphertext[0] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(ciphertext)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_should_throw_when_dek_base64_is_blank() {
        assertThatThrownBy(() -> new EnvDekCipherService(new RealnameDekProperties("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MBW_REALNAME_DEK_BASE64");
    }

    @Test
    void constructor_should_throw_when_dek_decodes_to_wrong_length() {
        // 16 bytes (AES-128 key) — not 32 (AES-256) as required
        byte[] shortKey = new byte[16];
        new SecureRandom().nextBytes(shortKey);
        String wrongLengthBase64 = Base64.getEncoder().encodeToString(shortKey);

        assertThatThrownBy(() -> new EnvDekCipherService(new RealnameDekProperties(wrongLengthBase64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    private static RealnameDekProperties validProperties() {
        byte[] dek = new byte[32];
        new SecureRandom().nextBytes(dek);
        return new RealnameDekProperties(Base64.getEncoder().encodeToString(dek));
    }
}
