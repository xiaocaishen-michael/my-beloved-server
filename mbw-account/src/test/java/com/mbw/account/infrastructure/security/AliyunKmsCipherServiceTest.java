package com.mbw.account.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AliyunKmsCipherServiceTest {

    private final AliyunKmsCipherService cipher = new AliyunKmsCipherService();

    @Test
    void encrypt_should_throw_UnsupportedOperationException_with_M3_marker() {
        assertThatThrownBy(() -> cipher.encrypt(new byte[] {1, 2, 3}))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("M3 placeholder");
    }

    @Test
    void decrypt_should_throw_UnsupportedOperationException_with_M3_marker() {
        assertThatThrownBy(() -> cipher.decrypt(new byte[] {1, 2, 3}))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("M3 placeholder");
    }
}
