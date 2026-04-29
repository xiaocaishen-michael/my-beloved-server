package com.mbw.account.domain.repository;

import com.mbw.account.domain.model.Credential;

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
}
