package com.mbw.account.infrastructure.persistence;

import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

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
}
