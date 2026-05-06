package com.mbw.account.infrastructure.persistence;

import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.Credential;
import com.mbw.account.domain.model.DisplayName;
import com.mbw.account.domain.model.PasswordCredential;
import com.mbw.account.domain.model.PasswordHash;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(AccountMapper.class);

    private AccountMapper() {}

    public static Account toDomain(AccountJpaEntity entity) {
        // ANONYMIZED rows have phone cleared (V10 + FR-003); construct
        // the PhoneNumber VO only when the column is non-null so we
        // don't blow up the load path on a legitimately anonymized row.
        PhoneNumber phone = entity.getPhone() == null ? null : new PhoneNumber(entity.getPhone());
        return Account.reconstitute(
                new AccountId(entity.getId()),
                phone,
                AccountStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getLastLoginAt(),
                toDisplayName(entity.getDisplayName(), entity.getId()),
                entity.getFreezeUntil(),
                entity.getPreviousPhoneHash());
    }

    /**
     * Decode a stored {@code display_name} string back into a
     * {@link DisplayName} VO. Persisted values were validated by the VO
     * on the way in, but the column predates {@link DisplayName} (a row
     * could carry corrupted bytes after a restore from an external
     * source, or a future tightening of FR-005 could orphan older
     * values). Tolerate by logging WARN + returning {@code null} so the
     * GET /me happy path keeps responding instead of 500-ing.
     */
    private static DisplayName toDisplayName(String raw, Long accountId) {
        if (raw == null) {
            return null;
        }
        try {
            return new DisplayName(raw);
        } catch (IllegalArgumentException ex) {
            LOG.warn("Account {} has corrupted display_name; coercing to null. Reason: {}", accountId, ex.getMessage());
            return null;
        }
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
        // ANONYMIZED accounts carry no phone (FR-003); keep the column
        // null on the entity so the UPDATE path actually clears it on
        // the FROZEN → ANONYMIZED save.
        entity.setPhone(account.phone() == null ? null : account.phone().e164());
        entity.setStatus(account.status() == null ? null : account.status().name());
        entity.setCreatedAt(account.createdAt());
        entity.setUpdatedAt(account.updatedAt());
        entity.setLastLoginAt(account.lastLoginAt());
        entity.setDisplayName(
                account.displayName() == null ? null : account.displayName().value());
        entity.setFreezeUntil(account.freezeUntil());
        entity.setPreviousPhoneHash(account.previousPhoneHash());
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
