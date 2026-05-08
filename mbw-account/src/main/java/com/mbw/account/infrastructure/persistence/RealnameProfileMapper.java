package com.mbw.account.infrastructure.persistence;

import com.mbw.account.domain.model.FailedReason;
import com.mbw.account.domain.model.RealnameProfile;
import com.mbw.account.domain.model.RealnameStatus;

/**
 * Hand-rolled mapper between {@link RealnameProfile} and
 * {@link RealnameProfileJpaEntity} (realname-verification spec T7).
 *
 * <p>Tasks.md mentions {@code @Mapper(componentModel = "spring")} (MapStruct),
 * but the project consistently hand-rolls mappers (see {@code AccountMapper}
 * javadoc for rationale): immutable domain types with custom factories +
 * value-object wrappers don't compose well with MapStruct's generated code,
 * and the mapping logic is small enough that hand rolling beats wiring the
 * annotation processor.
 *
 * <p>Stateless utility (private ctor + final + static-only).
 */
public final class RealnameProfileMapper {

    private RealnameProfileMapper() {}

    /**
     * Build a {@link RealnameProfile} from its persisted form. {@code id} is
     * never null on this path — every row carries an IDENTITY-assigned PK.
     */
    public static RealnameProfile toDomain(RealnameProfileJpaEntity entity) {
        return RealnameProfile.reconstitute(
                entity.getId(),
                entity.getAccountId(),
                RealnameStatus.valueOf(entity.getStatus()),
                entity.getRealNameEnc(),
                entity.getIdCardNoEnc(),
                entity.getIdCardHash(),
                entity.getProviderBizId(),
                entity.getVerifiedAt(),
                entity.getFailedReason() == null ? null : FailedReason.valueOf(entity.getFailedReason()),
                entity.getFailedAt(),
                entity.getRetryCount24h(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /**
     * Build a JPA entity from a {@link RealnameProfile}. A fresh (not-yet-saved)
     * profile passes {@code id = null} through, letting the JPA IDENTITY
     * column assign it on insert.
     */
    public static RealnameProfileJpaEntity toEntity(RealnameProfile profile) {
        RealnameProfileJpaEntity entity = new RealnameProfileJpaEntity();
        entity.setId(profile.id());
        entity.setAccountId(profile.accountId());
        entity.setStatus(profile.status().name());
        entity.setRealNameEnc(profile.realNameEnc());
        entity.setIdCardNoEnc(profile.idCardNoEnc());
        entity.setIdCardHash(profile.idCardHash());
        entity.setProviderBizId(profile.providerBizId());
        entity.setVerifiedAt(profile.verifiedAt());
        entity.setFailedReason(
                profile.failedReason() == null ? null : profile.failedReason().name());
        entity.setFailedAt(profile.failedAt());
        entity.setRetryCount24h(profile.retryCount24h());
        entity.setCreatedAt(profile.createdAt());
        entity.setUpdatedAt(profile.updatedAt());
        return entity;
    }
}
