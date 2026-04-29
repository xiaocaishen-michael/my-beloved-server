package com.mbw.account.infrastructure.persistence;

import com.mbw.account.domain.model.Credential;
import com.mbw.account.domain.repository.CredentialRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA-backed adapter for {@link CredentialRepository}. Domain code
 * sees only the {@code CredentialRepository} interface; Spring
 * autowires this concrete bean by type.
 */
@Repository
public class CredentialRepositoryImpl implements CredentialRepository {

    private final CredentialJpaRepository jpa;

    public CredentialRepositoryImpl(CredentialJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(Credential credential) {
        jpa.save(AccountMapper.toCredentialEntity(credential));
    }
}
