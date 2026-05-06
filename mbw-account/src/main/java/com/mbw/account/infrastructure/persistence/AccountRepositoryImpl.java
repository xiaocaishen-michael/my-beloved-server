package com.mbw.account.infrastructure.persistence;

import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed adapter for {@link AccountRepository}. Domain code never
 * sees this class — it consumes the {@code AccountRepository}
 * interface and Spring autowires this bean by type.
 *
 * <p>Per meta {@code modular-strategy.md} § "Repository Methods (方式 A)":
 * domain interface, infrastructure implementation, mapper bridges
 * persistence types back to domain types.
 */
@Repository
public class AccountRepositoryImpl implements AccountRepository {

    private final AccountJpaRepository jpa;

    public AccountRepositoryImpl(AccountJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Account> findByPhone(PhoneNumber phone) {
        return jpa.findByPhone(phone.e164()).map(AccountMapper::toDomain);
    }

    @Override
    public Optional<Account> findByPhoneForUpdate(PhoneNumber phone) {
        return jpa.findByPhoneForUpdate(phone.e164()).map(AccountMapper::toDomain);
    }

    @Override
    public Optional<Account> findById(AccountId accountId) {
        return jpa.findById(accountId.value()).map(AccountMapper::toDomain);
    }

    @Override
    public Optional<Account> findByIdForUpdate(AccountId accountId) {
        return jpa.findByIdForUpdate(accountId.value()).map(AccountMapper::toDomain);
    }

    @Override
    public List<AccountId> findFrozenWithExpiredGracePeriod(Instant now, int limit) {
        return jpa.findFrozenIdsWithExpiredGracePeriod(now, PageRequest.of(0, limit)).stream()
                .map(AccountId::new)
                .toList();
    }

    @Override
    public boolean existsByPhone(PhoneNumber phone) {
        return jpa.existsByPhone(phone.e164());
    }

    /**
     * Persist {@code account}. For a fresh in-memory account (id =
     * null), the JPA IDENTITY column generates one and we propagate it
     * back via {@link Account#assignId} so the same in-memory instance
     * is returned with its identity sealed.
     */
    @Override
    public Account save(Account account) {
        AccountJpaEntity entity = AccountMapper.toEntity(account);
        AccountJpaEntity saved = jpa.save(entity);
        if (account.id() == null) {
            account.assignId(new AccountId(saved.getId()));
        }
        return account;
    }

    /**
     * Targeted single-row UPDATE for {@code last_login_at} +
     * {@code updated_at}. {@code @Modifying} requires a transaction;
     * the use case layer typically owns the outer transaction, but we
     * also guard here so direct callers (tests / future read-paths) get
     * a session of their own.
     *
     * <p>The Spring Data {@code @Modifying} return value is the number
     * of rows affected; 0 means no row matched the supplied id, which
     * for this use case is a programming error (caller already loaded
     * the account). Surface as {@link IllegalStateException} per the
     * domain interface contract.
     */
    @Override
    @Transactional
    public void updateLastLoginAt(AccountId accountId, Instant lastLoginAt) {
        int affected = jpa.updateLastLoginAt(accountId.value(), lastLoginAt);
        if (affected == 0) {
            throw new IllegalStateException("No account row found for id=" + accountId.value());
        }
    }
}
