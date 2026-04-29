package com.mbw.account.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link CredentialJpaEntity}.
 *
 * <p>Package-private; consumed by {@link AccountRepositoryImpl} only.
 * Future {@code CredentialRepository} domain contract (when the use
 * cases need it directly) will adapt back through this interface.
 */
interface CredentialJpaRepository extends JpaRepository<CredentialJpaEntity, Long> {

    List<CredentialJpaEntity> findByAccountId(Long accountId);
}
