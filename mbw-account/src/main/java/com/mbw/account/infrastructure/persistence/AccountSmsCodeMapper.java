package com.mbw.account.infrastructure.persistence;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountSmsCode;
import com.mbw.account.domain.model.AccountSmsCodeId;

/**
 * Hand-rolled domain ↔ JPA mapper for {@link AccountSmsCode} —
 * same pattern as {@link RefreshTokenMapper} (no MapStruct; the
 * conversion is small enough that explicit code is clearer).
 */
public final class AccountSmsCodeMapper {

    private AccountSmsCodeMapper() {}

    public static AccountSmsCode toDomain(AccountSmsCodeJpaEntity entity) {
        return AccountSmsCode.reconstitute(
                new AccountSmsCodeId(entity.getId()),
                new AccountId(entity.getAccountId()),
                entity.getCodeHash(),
                entity.getPurpose(),
                entity.getExpiresAt(),
                entity.getUsedAt(),
                entity.getCreatedAt());
    }

    public static AccountSmsCodeJpaEntity toEntity(AccountSmsCode code) {
        AccountSmsCodeJpaEntity entity = new AccountSmsCodeJpaEntity();
        if (code.id() != null) {
            entity.setId(code.id().value());
        }
        entity.setAccountId(code.accountId().value());
        entity.setCodeHash(code.codeHash());
        entity.setPurpose(code.purpose());
        entity.setExpiresAt(code.expiresAt());
        entity.setUsedAt(code.usedAt());
        entity.setCreatedAt(code.createdAt());
        return entity;
    }
}
