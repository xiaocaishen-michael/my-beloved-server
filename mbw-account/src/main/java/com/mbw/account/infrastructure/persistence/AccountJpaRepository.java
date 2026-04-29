package com.mbw.account.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link AccountJpaEntity}.
 *
 * <p>Package-private interface — only {@link AccountRepositoryImpl}
 * inside the same package consumes it; the rest of the codebase
 * depends on the {@code AccountRepository} domain contract.
 */
interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, Long> {

    Optional<AccountJpaEntity> findByPhone(String phone);

    boolean existsByPhone(String phone);
}
