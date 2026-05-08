package com.mbw.account.domain.repository;

import com.mbw.account.domain.model.RealnameProfile;
import java.util.Optional;

/**
 * Domain-side persistence contract for {@link RealnameProfile}
 * (realname-verification spec T5).
 *
 * <p>Pure interface — the JPA-backed implementation in
 * {@code mbw-account.infrastructure.persistence} adapts to this contract per
 * meta {@code modular-strategy.md} § "Repository Methods (方式 A)". Domain
 * code never sees JPA types.
 *
 * <p>Surface intentionally narrow: {@code save} + 3 lookup paths. Per plan
 * D-004 (不可解绑约束物理化 / FR-015), {@code delete} / {@code deleteAll} /
 * {@code findAll} / {@code count} are <b>not</b> exposed — VERIFIED rows must
 * never be physically removed; account anonymization clears the PII columns
 * via a {@code save} of an updated profile, not a delete.
 */
public interface RealnameProfileRepository {

    /**
     * Look up the realname profile for an account, if one exists.
     * {@code account_id} carries a UNIQUE constraint at the DB level (V11),
     * so this lookup returns at most one row.
     */
    Optional<RealnameProfile> findByAccountId(long accountId);

    /**
     * Look up by {@code id_card_hash} — used by
     * {@code InitiateRealnameVerificationUseCase} to detect cross-account
     * collisions (FR-013 / SC-003). Backed by the partial unique index
     * {@code uk_realname_profile_id_card_hash}; non-null hashes are globally
     * unique.
     */
    Optional<RealnameProfile> findByIdCardHash(String idCardHash);

    /**
     * Look up by {@code provider_biz_id} — used by
     * {@code ConfirmRealnameVerificationUseCase} when the client polls
     * {@code GET /verifications/{providerBizId}} after the SDK liveness flow.
     */
    Optional<RealnameProfile> findByProviderBizId(String providerBizId);

    /**
     * Persist a new or updated realname profile. The implementation must
     * upsert on {@code account_id} (one profile per account) and surface DB
     * unique-constraint violations on {@code id_card_hash} as Spring's
     * {@code DataIntegrityViolationException} so the use case can map them
     * to {@code REALNAME_ID_CARD_OCCUPIED}.
     *
     * @return the persisted profile (with assigned id on first save)
     */
    RealnameProfile save(RealnameProfile profile);
}
