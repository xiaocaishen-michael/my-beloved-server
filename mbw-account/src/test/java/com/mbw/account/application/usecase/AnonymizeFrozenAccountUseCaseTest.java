package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.api.event.AccountAnonymizedEvent;
import com.mbw.account.application.command.AnonymizeFrozenAccountCommand;
import com.mbw.account.application.port.AnonymizeStrategy;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.PessimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class AnonymizeFrozenAccountUseCaseTest {

    private static final AccountId ACCOUNT_ID = new AccountId(42L);
    private static final PhoneNumber PHONE = new PhoneNumber("+8613800138000");
    private static final Instant CREATED_AT = Instant.parse("2026-04-21T03:00:00Z");
    private static final Instant FREEZE_UNTIL = CREATED_AT.plusSeconds(15L * 24 * 3600);
    private static final Instant NOW_AFTER_GRACE = FREEZE_UNTIL.plusSeconds(1);
    private static final String EXPECTED_PHONE_HASH =
            "ec61f3c620a98bdead8c1f1f0ae747abd1b62a0c2dba4fd4bc22cf0d1d8653e5";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AnonymizeStrategy strategyA;

    @Mock
    private AnonymizeStrategy strategyB;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(NOW_AFTER_GRACE, ZoneOffset.UTC);
    private AnonymizeFrozenAccountUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new AnonymizeFrozenAccountUseCase(
                accountRepository, List.of(strategyA, strategyB), eventPublisher, clock);
    }

    private AnonymizeFrozenAccountCommand cmd() {
        return new AnonymizeFrozenAccountCommand(ACCOUNT_ID);
    }

    private static Account frozenAccount() {
        return Account.reconstitute(
                ACCOUNT_ID,
                PHONE,
                AccountStatus.FROZEN,
                CREATED_AT,
                CREATED_AT,
                /* lastLoginAt= */ null,
                /* displayName= */ null,
                FREEZE_UNTIL);
    }

    @Test
    void should_transition_FROZEN_account_to_ANONYMIZED_with_hash_and_run_all_strategies() {
        Account account = frozenAccount();
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        useCase.execute(cmd());

        // Save called with status=ANONYMIZED + null phone + hash set
        ArgumentCaptor<Account> saveCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saveCaptor.capture());
        Account saved = saveCaptor.getValue();
        assertThat(saved.status()).isEqualTo(AccountStatus.ANONYMIZED);
        assertThat(saved.phone()).isNull();
        assertThat(saved.previousPhoneHash()).isEqualTo(EXPECTED_PHONE_HASH);

        // Both strategies invoked with the captured "now"
        verify(strategyA).apply(eq(ACCOUNT_ID), eq(NOW_AFTER_GRACE));
        verify(strategyB).apply(eq(ACCOUNT_ID), eq(NOW_AFTER_GRACE));

        // Event published last (after save + strategies)
        ArgumentCaptor<AccountAnonymizedEvent> eventCaptor = ArgumentCaptor.forClass(AccountAnonymizedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AccountAnonymizedEvent event = eventCaptor.getValue();
        assertThat(event.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(event.anonymizedAt()).isEqualTo(NOW_AFTER_GRACE);
    }

    @Test
    void should_throw_IllegalState_when_account_not_found() {
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(cmd()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Account not found");

        verify(strategyA, never()).apply(any(), any());
        verify(strategyB, never()).apply(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_when_account_already_has_null_phone_already_anonymized() {
        Account anonymized = Account.reconstitute(
                ACCOUNT_ID,
                /* phone= */ null,
                AccountStatus.ANONYMIZED,
                CREATED_AT,
                CREATED_AT,
                null,
                null,
                /* freezeUntil= */ null,
                "stale-hash");
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(anonymized));

        assertThatThrownBy(() -> useCase.execute(cmd()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null phone");

        verify(accountRepository, never()).save(any());
        verify(strategyA, never()).apply(any(), any());
    }

    @Test
    void should_throw_when_status_is_ACTIVE_state_machine_rejection() {
        Account active =
                Account.reconstitute(ACCOUNT_ID, PHONE, AccountStatus.ACTIVE, CREATED_AT, CREATED_AT, null, null, null);
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> useCase.execute(cmd()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FROZEN");

        verify(accountRepository, never()).save(any());
        verify(strategyA, never()).apply(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_when_freeze_until_in_future_grace_not_expired() {
        // freeze_until is well after the clock's "now"
        Instant farFuture = NOW_AFTER_GRACE.plusSeconds(10L * 24 * 3600);
        Account stillInGrace = Account.reconstitute(
                ACCOUNT_ID, PHONE, AccountStatus.FROZEN, CREATED_AT, CREATED_AT, null, null, farFuture);
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(stillInGrace));

        assertThatThrownBy(() -> useCase.execute(cmd()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("freeze_until");

        verify(accountRepository, never()).save(any());
        verify(strategyA, never()).apply(any(), any());
    }

    @Test
    void should_propagate_when_strategy_throws_for_TX_rollback() {
        Account account = frozenAccount();
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new RuntimeException("strategy A boom"))
                .when(strategyA)
                .apply(any(), any());

        assertThatThrownBy(() -> useCase.execute(cmd()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("strategy A boom");

        // strategyB should not run after A failed
        verify(strategyB, never()).apply(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_propagate_when_event_publisher_throws_for_TX_rollback() {
        Account account = frozenAccount();
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new RuntimeException("outbox boom"))
                .when(eventPublisher)
                .publishEvent(any(AccountAnonymizedEvent.class));

        assertThatThrownBy(() -> useCase.execute(cmd()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("outbox boom");
    }

    @Test
    void should_propagate_pessimistic_lock_failure_for_scheduler_to_count() {
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID))
                .thenThrow(new PessimisticLockingFailureException("lock timeout"));

        assertThatThrownBy(() -> useCase.execute(cmd())).isInstanceOf(PessimisticLockingFailureException.class);

        verify(strategyA, never()).apply(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
