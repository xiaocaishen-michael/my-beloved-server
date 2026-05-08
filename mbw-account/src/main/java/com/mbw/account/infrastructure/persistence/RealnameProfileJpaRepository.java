package com.mbw.account.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA repository for {@link RealnameProfileJpaEntity}
 * (realname-verification spec T7). The domain-facing
 * {@code RealnameProfileRepository} interface is implemented by
 * {@link RealnameProfileRepositoryImpl} which wraps this repo + mapper.
 *
 * <p>Only the lookups required by the use cases are exposed — no
 * {@code findAll} / {@code count} / {@code delete*} surface beyond what
 * Spring Data ships for free (which the use cases will not call, per
 * {@code RealnameProfileRepository}'s D-004 contract).
 */
public interface RealnameProfileJpaRepository extends JpaRepository<RealnameProfileJpaEntity, Long> {

    Optional<RealnameProfileJpaEntity> findByAccountId(long accountId);

    Optional<RealnameProfileJpaEntity> findByIdCardHash(String idCardHash);

    Optional<RealnameProfileJpaEntity> findByProviderBizId(String providerBizId);

    @Query("SELECT e FROM RealnameProfileJpaEntity e WHERE e.status = 'PENDING'"
            + " AND e.updatedAt < :threshold ORDER BY e.updatedAt ASC")
    List<RealnameProfileJpaEntity> findStalePendingOlderThan(Instant threshold, Pageable pageable);
}
