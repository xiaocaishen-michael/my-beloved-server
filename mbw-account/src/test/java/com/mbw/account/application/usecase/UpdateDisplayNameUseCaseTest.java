package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mbw.account.application.command.UpdateDisplayNameCommand;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UpdateDisplayNameUseCaseTest {

    private static final PhoneNumber PHONE = new PhoneNumber("+8613800138000");
    private static final AccountId ACCOUNT_ID = new AccountId(42L);
    private static final Instant CREATED_AT = Instant.parse("2026-05-02T10:00:00Z");

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private RateLimitService rateLimitService;

    @InjectMocks
    private UpdateDisplayNameUseCase useCase;

    @Test
    void should_change_displayName_via_state_machine_and_save_when_ACTIVE() {
        Account account = activeAccount(/* displayName= */ null);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);
        UpdateDisplayNameCommand cmd = new UpdateDisplayNameCommand(ACCOUNT_ID, "Alice");

        AccountProfileResult result = useCase.execute(cmd);

        assertThat(account.displayName()).isEqualTo(new DisplayName("Alice"));
        assertThat(result.displayName()).isEqualTo(new DisplayName("Alice"));
        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.status()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountRepository).save(account);
    }

    @Test
    void should_be_idempotent_when_same_value_submitted_twice() {
        Account account = activeAccount(new DisplayName("Alice"));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        AccountProfileResult result = useCase.execute(new UpdateDisplayNameCommand(ACCOUNT_ID, "Alice"));

        assertThat(result.displayName()).isEqualTo(new DisplayName("Alice"));
        verify(accountRepository).save(account);
    }

    @Test
    void should_propagate_IllegalArgumentException_when_displayName_is_invalid_empty_string() {
        UpdateDisplayNameCommand cmd = new UpdateDisplayNameCommand(ACCOUNT_ID, "");

        assertThatThrownBy(() -> useCase.execute(cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_DISPLAY_NAME");

        // anti-spend: invalid input must not hit the repository at all.
        verify(accountRepository, org.mockito.Mockito.never()).save(any(Account.class));
    }

    @Test
    void should_throw_AccountNotFoundException_when_findById_empty() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new UpdateDisplayNameCommand(ACCOUNT_ID, "Alice")))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void should_throw_AccountInactiveException_when_status_is_FROZEN() {
        Account frozen =
                Account.reconstitute(ACCOUNT_ID, PHONE, AccountStatus.FROZEN, CREATED_AT, CREATED_AT, null, null);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(frozen));

        assertThatThrownBy(() -> useCase.execute(new UpdateDisplayNameCommand(ACCOUNT_ID, "Alice")))
                .isInstanceOf(AccountInactiveException.class);

        verify(accountRepository, org.mockito.Mockito.never()).save(any(Account.class));
    }

    @Test
    void should_throw_AccountInactiveException_when_status_is_ANONYMIZED() {
        Account anonymized =
                Account.reconstitute(ACCOUNT_ID, PHONE, AccountStatus.ANONYMIZED, CREATED_AT, CREATED_AT, null, null);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(anonymized));

        assertThatThrownBy(() -> useCase.execute(new UpdateDisplayNameCommand(ACCOUNT_ID, "Alice")))
                .isInstanceOf(AccountInactiveException.class);
    }

    @Test
    void should_propagate_RateLimitedException_without_calling_repository() {
        doThrow(new RateLimitedException("me-patch:42", Duration.ofSeconds(30)))
                .when(rateLimitService)
                .consumeOrThrow(contains("me-patch:" + ACCOUNT_ID.value()), any(Bandwidth.class));

        assertThatThrownBy(() -> useCase.execute(new UpdateDisplayNameCommand(ACCOUNT_ID, "Alice")))
                .isInstanceOf(RateLimitedException.class);

        verifyNoInteractions(accountRepository);
    }

    private static Account activeAccount(DisplayName displayName) {
        return Account.reconstitute(
                ACCOUNT_ID, PHONE, AccountStatus.ACTIVE, CREATED_AT, CREATED_AT, /* lastLoginAt= */ null, displayName);
    }
}
