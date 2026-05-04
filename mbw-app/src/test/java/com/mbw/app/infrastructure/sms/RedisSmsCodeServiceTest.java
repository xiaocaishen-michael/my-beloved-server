package com.mbw.app.infrastructure.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.domain.model.PasswordHash;
import com.mbw.account.domain.repository.VerificationCodeRepository;
import com.mbw.account.domain.repository.VerificationCodeRepository.AttemptOutcome;
import com.mbw.account.domain.service.PasswordHasher;
import com.mbw.shared.api.sms.SmsCodeService;
import com.mbw.shared.web.RateLimitedException;
import java.security.SecureRandom;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RedisSmsCodeServiceTest {

    private static final String PHONE = "+8613800138000";
    private static final String STORED_HASH = "$2a$08$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Mock
    private VerificationCodeRepository repository;

    @Mock
    private PasswordHasher passwordHasher;

    @Test
    void generateAndStore_should_return_six_digit_plaintext_and_store_hash() {
        // Use a deterministic SecureRandom so the assertion is stable
        SecureRandom seeded = new SecureRandom() {
            @Override
            public int nextInt(int bound) {
                return 123456;
            }
        };
        SmsCodeService service = new RedisSmsCodeService(repository, passwordHasher, seeded);
        when(passwordHasher.hash("123456")).thenReturn(new PasswordHash(STORED_HASH));
        when(repository.storeIfAbsent(any(), eq(STORED_HASH), any())).thenReturn(true);

        String code = service.generateAndStore(PHONE);

        assertThat(code).matches("^\\d{6}$");
        assertThat(code).isEqualTo("123456");
        verify(repository).storeIfAbsent(any(), eq(STORED_HASH), eq(RedisSmsCodeService.CODE_TTL));
    }

    @Test
    void generateAndStore_should_throw_RateLimited_when_pending_code_already_exists() {
        SmsCodeService service = new RedisSmsCodeService(repository, passwordHasher, new SecureRandom());
        when(passwordHasher.hash(any())).thenReturn(new PasswordHash(STORED_HASH));
        when(repository.storeIfAbsent(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.generateAndStore(PHONE))
                .isInstanceOf(RateLimitedException.class)
                .satisfies(ex -> {
                    RateLimitedException rle = (RateLimitedException) ex;
                    assertThat(rle.getLimitKey()).isEqualTo("sms-code-pending:" + PHONE);
                    assertThat(rle.getRetryAfter()).isEqualTo(RedisSmsCodeService.CODE_TTL);
                });
    }

    @Test
    void verify_should_return_success_and_delete_when_code_matches() {
        SmsCodeService service = new RedisSmsCodeService(repository, passwordHasher, new SecureRandom());
        when(repository.findHashByPhone(any())).thenReturn(Optional.of(STORED_HASH));
        when(passwordHasher.matches(eq("654321"), any())).thenReturn(true);

        com.mbw.shared.api.sms.AttemptOutcome outcome = service.verify(PHONE, "654321");

        assertThat(outcome.success()).isTrue();
        verify(repository).delete(any());
        verify(repository, never()).incrementAttemptOrInvalidate(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void verify_should_return_failure_with_attempt_count_on_mismatch() {
        SmsCodeService service = new RedisSmsCodeService(repository, passwordHasher, new SecureRandom());
        when(repository.findHashByPhone(any())).thenReturn(Optional.of(STORED_HASH));
        when(passwordHasher.matches(any(), any())).thenReturn(false);
        when(repository.incrementAttemptOrInvalidate(any(), eq(RedisSmsCodeService.MAX_ATTEMPTS)))
                .thenReturn(new AttemptOutcome(2, false));

        com.mbw.shared.api.sms.AttemptOutcome outcome = service.verify(PHONE, "wrong");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.invalidated()).isFalse();
        verify(repository, never()).delete(any());
    }

    @Test
    void verify_should_return_invalidated_when_no_active_code() {
        SmsCodeService service = new RedisSmsCodeService(repository, passwordHasher, new SecureRandom());
        when(repository.findHashByPhone(any())).thenReturn(Optional.empty());

        com.mbw.shared.api.sms.AttemptOutcome outcome = service.verify(PHONE, "123456");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.invalidated()).isTrue();
        verify(passwordHasher, never()).matches(any(), any());
    }
}
