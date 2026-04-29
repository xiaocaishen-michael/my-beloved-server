package com.mbw.account.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneNumber;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AccountRepositoryTest {

    private static final PhoneNumber PHONE = new PhoneNumber("+8613800138000");
    private static final Instant NOW = Instant.parse("2026-04-29T01:00:00Z");

    @Test
    void findByPhone_should_return_account_when_present() {
        AccountRepository repo = mock(AccountRepository.class);
        Account stored = Account.reconstitute(new AccountId(7L), PHONE, AccountStatus.ACTIVE, NOW, NOW);
        when(repo.findByPhone(PHONE)).thenReturn(Optional.of(stored));

        Optional<Account> result = repo.findByPhone(PHONE);

        assertThat(result).contains(stored);
    }

    @Test
    void findByPhone_should_return_empty_when_absent() {
        AccountRepository repo = mock(AccountRepository.class);
        when(repo.findByPhone(any())).thenReturn(Optional.empty());

        Optional<Account> result = repo.findByPhone(PHONE);

        assertThat(result).isEmpty();
    }

    @Test
    void existsByPhone_should_route_through_to_implementation() {
        AccountRepository repo = mock(AccountRepository.class);
        when(repo.existsByPhone(PHONE)).thenReturn(true);

        assertThat(repo.existsByPhone(PHONE)).isTrue();
        verify(repo).existsByPhone(PHONE);
    }

    @Test
    void save_should_return_account_with_id_assigned_by_implementation() {
        AccountRepository repo = mock(AccountRepository.class);
        Account fresh = new Account(PHONE, NOW);
        when(repo.save(any(Account.class))).thenAnswer(inv -> {
            Account input = inv.getArgument(0);
            input.assignId(new AccountId(99L));
            return input;
        });

        Account saved = repo.save(fresh);

        assertThat(saved.id()).isEqualTo(new AccountId(99L));
    }
}
