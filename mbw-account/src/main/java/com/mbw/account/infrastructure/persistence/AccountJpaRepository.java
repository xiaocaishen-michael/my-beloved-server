package com.mbw.account.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Targeted UPDATE for the login-by-phone-sms use case (FR-004 / V3
     * migration). Updates {@code last_login_at} + {@code updated_at} in
     * one statement so concurrent registers/saves do not contend with
     * the login bookkeeping write.
     */
    @Modifying
    @Query("UPDATE AccountJpaEntity a SET a.lastLoginAt = :lastLoginAt, a.updatedAt = :lastLoginAt"
            + " WHERE a.id = :accountId")
    int updateLastLoginAt(@Param("accountId") Long accountId, @Param("lastLoginAt") Instant lastLoginAt);
}
