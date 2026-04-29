package com.mbw.shared.api.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SmsCodeServiceTest {

    @Test
    void should_return_plaintext_from_generateAndStore_for_SMS_delivery() {
        AtomicReference<String> capturedPhone = new AtomicReference<>();
        SmsCodeService service = new SmsCodeService() {
            @Override
            public String generateAndStore(String phone) {
                capturedPhone.set(phone);
                return "123456";
            }

            @Override
            public AttemptOutcome verify(String phone, String code) {
                throw new UnsupportedOperationException();
            }
        };

        String code = service.generateAndStore("+8613800138000");

        assertThat(capturedPhone.get()).isEqualTo("+8613800138000");
        assertThat(code).isEqualTo("123456");
    }

    @Test
    void should_return_AttemptOutcome_from_verify() {
        SmsCodeService service = new SmsCodeService() {
            @Override
            public String generateAndStore(String phone) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AttemptOutcome verify(String phone, String code) {
                return new AttemptOutcome(true, 0, false);
            }
        };

        AttemptOutcome outcome = service.verify("+8613800138000", "123456");

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.attempts()).isZero();
        assertThat(outcome.invalidated()).isFalse();
    }

    @Test
    void AttemptOutcome_should_carry_failure_with_attempt_count_and_invalidated_flag() {
        AttemptOutcome failedRetryable = new AttemptOutcome(false, 2, false);
        AttemptOutcome failedFinal = new AttemptOutcome(false, 3, true);

        assertThat(failedRetryable.success()).isFalse();
        assertThat(failedRetryable.attempts()).isEqualTo(2);
        assertThat(failedRetryable.invalidated()).isFalse();

        assertThat(failedFinal.success()).isFalse();
        assertThat(failedFinal.attempts()).isEqualTo(3);
        assertThat(failedFinal.invalidated()).isTrue();
    }

    @Test
    void AttemptOutcome_should_reject_negative_attempts() {
        assertThatThrownBy(() -> new AttemptOutcome(false, -1, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempts");
    }
}
