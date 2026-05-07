package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mbw.account.application.result.AccountProfileResult;
import com.mbw.account.domain.exception.AccountInactiveException;
import com.mbw.account.domain.exception.AccountNotFoundException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.DisplayName;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.shared.web.RateLimitService;
import com.mbw.shared.web.RateLimitedException;
import io.github.bucket4j.Bandwidth;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetAccountProfileUseCaseTest {

    private static final PhoneNumber PHONE = new PhoneNumber("+8613800138000");
    private static final AccountId ACCOUNT_ID = new AccountId(42L);
    private static final Instant CREATED_AT = Instant.parse("2026-05-02T10:00:00Z");

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private RateLimitService rateLimitService;

    @InjectMocks
    private GetAccountProfileUseCase useCase;

    @BeforeEach
    void noOpRateLimit() {
        // default behavior: rate limit allows; tests that need denial override.
    }

    @Test
    void should_return_profile_when_account_is_ACTIVE_with_null_displayName() {
        Account account = activeAccount(/* displayName= */ null);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        AccountProfileResult result = useCase.execute(ACCOUNT_ID);

        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.phone()).isEqualTo(PHONE);
        assertThat(result.displayName()).isNull();
        assertThat(result.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(result.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void should_return_profile_when_account_is_ACTIVE_with_displayName() {
        DisplayName displayName = new DisplayName("Alice");
        Account account = activeAccount(displayName);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        AccountProfileResult result = useCase.execute(ACCOUNT_ID);

        assertThat(result.phone()).isEqualTo(PHONE);
        assertThat(result.displayName()).isEqualTo(displayName);
    }

    @Test
    void should_throw_AccountNotFoundException_when_findById_empty() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(ACCOUNT_ID)).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void should_throw_AccountInactiveException_when_status_is_FROZEN() {
        Account frozen =
                Account.reconstitute(ACCOUNT_ID, PHONE, AccountStatus.FROZEN, CREATED_AT, CREATED_AT, null, null);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(frozen));

        assertThatThrownBy(() -> useCase.execute(ACCOUNT_ID)).isInstanceOf(AccountInactiveException.class);
    }

    @Test
    void should_throw_AccountInactiveException_when_status_is_ANONYMIZED() {
        Account anonymized =
                Account.reconstitute(ACCOUNT_ID, PHONE, AccountStatus.ANONYMIZED, CREATED_AT, CREATED_AT, null, null);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(anonymized));

        assertThatThrownBy(() -> useCase.execute(ACCOUNT_ID)).isInstanceOf(AccountInactiveException.class);
    }

    @Test
    void should_propagate_RateLimitedException_without_calling_repository() {
        doThrow(new RateLimitedException("me-get:42", Duration.ofSeconds(30)))
                .when(rateLimitService)
                .consumeOrThrow(contains("me-get:" + ACCOUNT_ID.value()), any(Bandwidth.class));

        assertThatThrownBy(() -> useCase.execute(ACCOUNT_ID)).isInstanceOf(RateLimitedException.class);

        verifyNoInteractions(accountRepository);
    }

    private static Account activeAccount(DisplayName displayName) {
        return Account.reconstitute(
                ACCOUNT_ID, PHONE, AccountStatus.ACTIVE, CREATED_AT, CREATED_AT, /* lastLoginAt= */ null, displayName);
    }
}
