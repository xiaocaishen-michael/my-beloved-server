package com.mbw.account.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.DisplayName;
import com.mbw.account.domain.model.PhoneNumber;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the displayName ↔ JPA mapping branch added in T3.
 * The aggregate-level mapping (id / phone / status / timestamps) is
 * already covered by {@link AccountRepositoryImplIT}; these tests focus
 * on the new field plus the corruption-tolerant decode path described
 * in plan.md § AccountMapper.
 */
class AccountMapperTest {

    private static final PhoneNumber PHONE = new PhoneNumber("+8613800138000");
    private static final Instant CREATED_AT = Instant.parse("2026-05-02T10:00:00Z");

    @Test
    void toEntity_should_carry_null_displayName() {
        Account account = AccountStateMachine.activate(new Account(PHONE, CREATED_AT), CREATED_AT);

        AccountJpaEntity entity = AccountMapper.toEntity(account);

        assertThat(entity.getDisplayName()).isNull();
    }

    @Test
    void toEntity_should_carry_set_displayName_value() {
        Account account = Account.reconstitute(
                new AccountId(1L),
                PHONE,
                AccountStatus.ACTIVE,
                CREATED_AT,
                CREATED_AT,
                /* lastLoginAt= */ null,
                new DisplayName("Alice"));

        AccountJpaEntity entity = AccountMapper.toEntity(account);

        assertThat(entity.getDisplayName()).isEqualTo("Alice");
    }

    @Test
    void toDomain_should_propagate_null_displayName() {
        AccountJpaEntity entity = newEntity(/* displayName= */ null);

        Account account = AccountMapper.toDomain(entity);

        assertThat(account.displayName()).isNull();
    }

    @Test
    void toDomain_should_rebuild_valid_displayName_into_value_object() {
        AccountJpaEntity entity = newEntity("Alice");

        Account account = AccountMapper.toDomain(entity);

        assertThat(account.displayName()).isEqualTo(new DisplayName("Alice"));
    }

    @Test
    void toDomain_should_swallow_corrupted_displayName_to_null() {
        // DB persisted a value that would fail today's DisplayName
        // constructor (e.g. a control character that slipped through an
        // older validator). Mapper must tolerate without throwing — so the
        // GET /me happy path keeps responding instead of 500-ing.
        String corrupted = "x" + (char) 0x0007 + "y";
        AccountJpaEntity entity = newEntity(corrupted);

        Account account = AccountMapper.toDomain(entity);

        assertThat(account.displayName()).isNull();
    }

    private static AccountJpaEntity newEntity(String displayName) {
        AccountJpaEntity entity = new AccountJpaEntity();
        entity.setId(1L);
        entity.setPhone(PHONE.e164());
        entity.setStatus(AccountStatus.ACTIVE.name());
        entity.setCreatedAt(CREATED_AT);
        entity.setUpdatedAt(CREATED_AT);
        entity.setDisplayName(displayName);
        return entity;
    }
}
