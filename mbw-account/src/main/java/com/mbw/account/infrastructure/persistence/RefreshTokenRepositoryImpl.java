package com.mbw.account.infrastructure.persistence;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.RefreshTokenHash;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.model.RefreshTokenRecordId;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed adapter for {@link RefreshTokenRepository}. Domain code
 * sees only the interface; Spring autowires this concrete bean by
 * type. Mirror of {@link AccountRepositoryImpl}'s structure.
 */
@Repository
public class RefreshTokenRepositoryImpl implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpa;

    public RefreshTokenRepositoryImpl(RefreshTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public RefreshTokenRecord save(RefreshTokenRecord record) {
        RefreshTokenJpaEntity saved = jpa.save(RefreshTokenMapper.toEntity(record));
        return RefreshTokenMapper.toDomain(saved);
    }

    @Override
    public Optional<RefreshTokenRecord> findByTokenHash(RefreshTokenHash hash) {
        return jpa.findByTokenHash(hash.value()).map(RefreshTokenMapper::toDomain);
    }

    @Override
    @Transactional
    public int revoke(RefreshTokenRecordId id, Instant revokedAt) {
        // Returns 0 if the record was already revoked (concurrent rotation
        // lost the race) — the caller is responsible for surfacing that
        // as a rotation failure when concurrency control is needed.
        return jpa.revoke(id.value(), revokedAt);
    }

    @Override
    @Transactional
    public int revokeAllForAccount(AccountId accountId, Instant revokedAt) {
        return jpa.revokeAllForAccount(accountId.value(), revokedAt);
    }
}
