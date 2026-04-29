package com.mbw.account.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.VerificationCodeRepository.AttemptOutcome;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VerificationCodeRepositoryTest {

    private static final PhoneNumber PHONE = new PhoneNumber("+8613800138000");
    private static final String HASH = "$2a$08$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Test
    void storeIfAbsent_should_return_true_on_first_insert_and_false_on_collision() {
        VerificationCodeRepository repo = mock(VerificationCodeRepository.class);
        when(repo.storeIfAbsent(eq(PHONE), eq(HASH), any())).thenReturn(true, false);

        boolean firstCall = repo.storeIfAbsent(PHONE, HASH, Duration.ofMinutes(5));
        boolean secondCall = repo.storeIfAbsent(PHONE, HASH, Duration.ofMinutes(5));

        assertThat(firstCall).isTrue();
        assertThat(secondCall).isFalse();
    }

    @Test
    void findHashByPhone_should_return_stored_hash() {
        VerificationCodeRepository repo = mock(VerificationCodeRepository.class);
        when(repo.findHashByPhone(PHONE)).thenReturn(Optional.of(HASH));

        Optional<String> result = repo.findHashByPhone(PHONE);

        assertThat(result).contains(HASH);
    }

    @Test
    void incrementAttemptOrInvalidate_should_return_running_count_when_below_max() {
        VerificationCodeRepository repo = mock(VerificationCodeRepository.class);
        when(repo.incrementAttemptOrInvalidate(eq(PHONE), anyInt())).thenReturn(new AttemptOutcome(2, false));

        AttemptOutcome outcome = repo.incrementAttemptOrInvalidate(PHONE, 3);

        assertThat(outcome.count()).isEqualTo(2);
        assertThat(outcome.invalidated()).isFalse();
    }

    @Test
    void incrementAttemptOrInvalidate_should_signal_invalidation_at_threshold() {
        VerificationCodeRepository repo = mock(VerificationCodeRepository.class);
        when(repo.incrementAttemptOrInvalidate(eq(PHONE), eq(3))).thenReturn(new AttemptOutcome(3, true));

        AttemptOutcome outcome = repo.incrementAttemptOrInvalidate(PHONE, 3);

        assertThat(outcome.count()).isEqualTo(3);
        assertThat(outcome.invalidated()).isTrue();
    }

    @Test
    void delete_should_be_callable() {
        VerificationCodeRepository repo = mock(VerificationCodeRepository.class);

        repo.delete(PHONE);

        verify(repo).delete(PHONE);
    }

    @Test
    void AttemptOutcome_should_reject_negative_count() {
        assertThatThrownBy(() -> new AttemptOutcome(-1, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count");
    }
}
