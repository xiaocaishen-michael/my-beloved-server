package com.mbw.shared.api.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class SmsCodePlaintextGeneratorTest {

    @Test
    void generateSixDigit_returns_dev_fixed_code_when_env_is_six_digits() {
        SecureRandom unused = new SecureRandom() {
            @Override
            public int nextInt(int bound) {
                throw new AssertionError("random should not be called when devFixedCode is set");
            }
        };
        SmsCodePlaintextGenerator gen = new SmsCodePlaintextGenerator("999999", unused);

        assertThat(gen.generateSixDigit()).isEqualTo("999999");
    }

    @Test
    void generateSixDigit_falls_back_to_random_when_env_is_blank() {
        SecureRandom seeded = new SecureRandom() {
            @Override
            public int nextInt(int bound) {
                return 42;
            }
        };
        SmsCodePlaintextGenerator gen = new SmsCodePlaintextGenerator("", seeded);

        assertThat(gen.generateSixDigit()).isEqualTo("000042");
    }

    @Test
    void generateSixDigit_falls_back_to_random_when_env_is_too_short() {
        SecureRandom seeded = new SecureRandom() {
            @Override
            public int nextInt(int bound) {
                return 7;
            }
        };
        SmsCodePlaintextGenerator gen = new SmsCodePlaintextGenerator("12345", seeded);

        assertThat(gen.generateSixDigit()).isEqualTo("000007");
    }

    @Test
    void generateSixDigit_falls_back_to_random_when_env_is_non_numeric() {
        SecureRandom seeded = new SecureRandom() {
            @Override
            public int nextInt(int bound) {
                return 100;
            }
        };
        SmsCodePlaintextGenerator gen = new SmsCodePlaintextGenerator("abc123", seeded);

        assertThat(gen.generateSixDigit()).isEqualTo("000100");
    }

    @Test
    void generateSixDigit_falls_back_to_random_when_env_is_null() {
        SecureRandom seeded = new SecureRandom() {
            @Override
            public int nextInt(int bound) {
                return 654321;
            }
        };
        SmsCodePlaintextGenerator gen = new SmsCodePlaintextGenerator(null, seeded);

        assertThat(gen.generateSixDigit()).isEqualTo("654321");
    }
}
