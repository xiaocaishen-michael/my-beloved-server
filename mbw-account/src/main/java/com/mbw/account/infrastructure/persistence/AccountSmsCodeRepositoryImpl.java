package com.mbw.account.infrastructure.persistence;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountSmsCode;
import com.mbw.account.domain.model.AccountSmsCodeId;
import com.mbw.account.domain.model.AccountSmsCodePurpose;
import com.mbw.account.domain.repository.AccountSmsCodeRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed adapter for {@link AccountSmsCodeRepository}. Domain code
 * sees only the interface; Spring autowires this concrete bean by type.
 * Mirror of {@link RefreshTokenRepositoryImpl}'s structure.
 */
@Repository
public class AccountSmsCodeRepositoryImpl implements AccountSmsCodeRepository {

    private final AccountSmsCodeJpaRepository jpa;

    public AccountSmsCodeRepositoryImpl(AccountSmsCodeJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public AccountSmsCode save(AccountSmsCode code) {
        AccountSmsCodeJpaEntity saved = jpa.save(AccountSmsCodeMapper.toEntity(code));
        return AccountSmsCodeMapper.toDomain(saved);
    }

    @Override
    public Optional<AccountSmsCode> findActiveByPurposeAndAccountId(
            AccountSmsCodePurpose purpose, AccountId accountId, Instant now) {
        return jpa.findActive(accountId.value(), purpose, now).map(AccountSmsCodeMapper::toDomain);
    }

    @Override
    @Transactional
    public void markUsed(AccountSmsCodeId id, Instant now) {
        jpa.markUsed(id.value(), now);
    }

    @Override
    @Transactional
    public void deleteAllByAccountId(AccountId accountId) {
        jpa.deleteByAccountId(accountId.value());
    }
}
