package com.mbw.account.infrastructure.persistence;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.RefreshTokenHash;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.model.RefreshTokenRecordId;

/**
 * Hand-rolled domain ↔ JPA mapper for {@link RefreshTokenRecord} —
 * same pattern as {@link AccountMapper} (no MapStruct, the conversion
 * is small enough that explicit code is clearer than annotation
 * processor configuration).
 */
public final class RefreshTokenMapper {

    private RefreshTokenMapper() {}

    public static RefreshTokenRecord toDomain(RefreshTokenJpaEntity entity) {
        return RefreshTokenRecord.reconstitute(
                new RefreshTokenRecordId(entity.getId()),
                new RefreshTokenHash(entity.getTokenHash()),
                new AccountId(entity.getAccountId()),
                entity.getExpiresAt(),
                entity.getRevokedAt(),
                entity.getCreatedAt());
    }

    public static RefreshTokenJpaEntity toEntity(RefreshTokenRecord record) {
        RefreshTokenJpaEntity entity = new RefreshTokenJpaEntity();
        if (record.id() != null) {
            entity.setId(record.id().value());
        }
        entity.setTokenHash(record.tokenHash().value());
        entity.setAccountId(record.accountId().value());
        entity.setExpiresAt(record.expiresAt());
        entity.setRevokedAt(record.revokedAt());
        entity.setCreatedAt(record.createdAt());
        return entity;
    }
}
