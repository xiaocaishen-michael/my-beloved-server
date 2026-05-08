package com.mbw.account.infrastructure.persistence;

import com.mbw.account.domain.model.RealnameProfile;
import com.mbw.account.domain.repository.RealnameProfileRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

/**
 * JPA-backed adapter for {@link RealnameProfileRepository}
 * (realname-verification spec T7). Domain code never sees this class — it
 * consumes the {@code RealnameProfileRepository} interface and Spring
 * autowires this bean by type.
 *
 * <p>Per meta {@code modular-strategy.md} § "Repository Methods (方式 A)":
 * domain interface, infrastructure implementation, hand-rolled mapper bridges
 * persistence types back to domain types.
 */
@Repository
public class RealnameProfileRepositoryImpl implements RealnameProfileRepository {

    private final RealnameProfileJpaRepository jpa;

    public RealnameProfileRepositoryImpl(RealnameProfileJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<RealnameProfile> findByAccountId(long accountId) {
        return jpa.findByAccountId(accountId).map(RealnameProfileMapper::toDomain);
    }

    @Override
    public Optional<RealnameProfile> findByIdCardHash(String idCardHash) {
        return jpa.findByIdCardHash(idCardHash).map(RealnameProfileMapper::toDomain);
    }

    @Override
    public Optional<RealnameProfile> findByProviderBizId(String providerBizId) {
        return jpa.findByProviderBizId(providerBizId).map(RealnameProfileMapper::toDomain);
    }

    /**
     * Persist (insert or update) a {@code RealnameProfile}. Because the
     * domain aggregate is immutable, we cannot mutate the in-memory
     * instance with the assigned id; instead we round-trip through the
     * mapper to return a fresh {@link RealnameProfile} with the persisted
     * id stamped in.
     */
    @Override
    public RealnameProfile save(RealnameProfile profile) {
        RealnameProfileJpaEntity entity = RealnameProfileMapper.toEntity(profile);
        RealnameProfileJpaEntity saved = jpa.save(entity);
        return RealnameProfileMapper.toDomain(saved);
    }

    @Override
    public List<RealnameProfile> findStalePendingOlderThan(Instant threshold, int limit) {
        return jpa.findStalePendingOlderThan(threshold, PageRequest.of(0, limit)).stream()
                .map(RealnameProfileMapper::toDomain)
                .toList();
    }
}
