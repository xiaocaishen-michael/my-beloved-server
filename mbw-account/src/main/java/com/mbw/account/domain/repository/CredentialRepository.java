package com.mbw.account.domain.repository;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.Credential;
import com.mbw.account.domain.model.PasswordCredential;
import java.util.Optional;

/**
 * Domain-side persistence contract for the sealed
 * {@link Credential} hierarchy. Implemented by the JPA adapter in
 * {@code mbw-account.infrastructure.persistence}; domain code never
 * sees JPA types.
 */
public interface CredentialRepository {

    /**
     * Persist a new credential row. Concrete subtype
     * (PhoneCredential / PasswordCredential) drives which payload
     * column is populated; the V2 CHECK constraint guarantees the
     * other column stays NULL.
     */
    void save(Credential credential);

    /**
     * Look up the PASSWORD credential for an account, if one exists.
     * Used by {@code LoginByPasswordUseCase} (FR-009) — when this
     * returns empty, the use case substitutes
     * {@code TimingDefenseExecutor.DUMMY_HASH} so the BCrypt verify
     * cost stays constant whether or not a password is set.
     */
    Optional<PasswordCredential> findPasswordCredentialByAccountId(AccountId accountId);
}
