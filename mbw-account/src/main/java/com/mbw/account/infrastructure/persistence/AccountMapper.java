package com.mbw.account.infrastructure.persistence;

import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.Credential;
import com.mbw.account.domain.model.PasswordCredential;
import com.mbw.account.domain.model.PasswordHash;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;

/**
 * Hand-rolled mapper between domain types and JPA entities.
 *
 * <p>plan.md and tasks.md mention MapStruct, but our domain types use
 * record-style construction with custom factories ({@code
 * Account.reconstitute}, sealed {@code Credential} hierarchy) and
 * value-object wrappers ({@code PhoneNumber}, {@code AccountId}).
 * MapStruct's generated code does not handle that pattern cleanly
 * without {@code @ObjectFactory} hooks plus type converters per VO.
 * The conversion logic is small enough that a hand-rolled mapper is
 * less infrastructure for the same outcome — and removes the need to
 * configure the annotation processor in {@code mbw-account/pom.xml}.
 *
 * <p>Stateless utility (private ctor + final + static-only).
 */
public final class AccountMapper {

    private AccountMapper() {}

    public static Account toDomain(AccountJpaEntity entity) {
        return Account.reconstitute(
                new AccountId(entity.getId()),
                new PhoneNumber(entity.getPhone()),
                AccountStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /**
     * Build a JPA entity from a domain Account. A fresh (not-yet-saved)
     * Account passes id=null through to the entity, letting JPA's
     * IDENTITY generation assign it on insert.
     */
    public static AccountJpaEntity toEntity(Account account) {
        AccountJpaEntity entity = new AccountJpaEntity();
        if (account.id() != null) {
            entity.setId(account.id().value());
        }
        entity.setPhone(account.phone().e164());
        entity.setStatus(account.status() == null ? null : account.status().name());
        entity.setCreatedAt(account.createdAt());
        entity.setUpdatedAt(account.updatedAt());
        return entity;
    }

    /**
     * Build a credential entity from a sealed {@link Credential}. The
     * type discriminator ('PHONE' / 'PASSWORD') drives which payload
     * column is populated; the V2 CHECK constraint on
     * {@code chk_credential_password_hash_required} guarantees the
     * other column stays null.
     */
    public static CredentialJpaEntity toCredentialEntity(Credential credential) {
        CredentialJpaEntity entity = new CredentialJpaEntity();
        entity.setAccountId(credential.account().value());
        entity.setCreatedAt(java.time.Instant.now());
        switch (credential) {
            case PhoneCredential phone -> {
                entity.setType("PHONE");
                entity.setLastUsedAt(phone.lastUsedAt());
            }
            case PasswordCredential password -> {
                entity.setType("PASSWORD");
                entity.setPasswordHash(password.hash().value());
                entity.setCreatedAt(password.createdAt());
            }
        }
        return entity;
    }

    /**
     * Reconstruct a PASSWORD {@link Credential} from its persisted form.
     *
     * <p>Only {@link PasswordCredential} is supported here.
     * {@link PhoneCredential} reconstruction needs the phone string
     * which lives on the parent {@link Account} aggregate, not on the
     * credential row — when a use case needs PHONE rebuild it should
     * be done at the use case level using the loaded Account. Throwing
     * for unknown / PHONE types until a concrete need arises (avoids
     * speculative API surface).
     */
    public static Credential toCredentialDomain(CredentialJpaEntity entity, PhoneNumber accountPhone) {
        AccountId accountId = new AccountId(entity.getAccountId());
        return switch (entity.getType()) {
            case "PHONE" -> new PhoneCredential(accountId, accountPhone, entity.getLastUsedAt());
            case "PASSWORD" ->
                new PasswordCredential(accountId, new PasswordHash(entity.getPasswordHash()), entity.getCreatedAt());
            default -> throw new IllegalStateException("Unknown credential type: " + entity.getType());
        };
    }
}
