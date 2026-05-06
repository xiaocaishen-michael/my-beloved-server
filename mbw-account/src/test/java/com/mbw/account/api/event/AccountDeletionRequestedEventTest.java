package com.mbw.account.api.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.account.domain.model.AccountId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccountDeletionRequestedEventTest {

    private static final AccountId ACCOUNT_ID = new AccountId(42L);
    private static final Instant FREEZE_AT = Instant.parse("2026-05-06T00:00:00Z");
    private static final Instant FREEZE_UNTIL = FREEZE_AT.plusSeconds(15L * 24 * 3600);

    @Test
    void fields_should_be_accessible_via_record_accessors() {
        AccountDeletionRequestedEvent event =
                new AccountDeletionRequestedEvent(ACCOUNT_ID, FREEZE_AT, FREEZE_UNTIL, FREEZE_AT);

        assertThat(event.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(event.freezeAt()).isEqualTo(FREEZE_AT);
        assertThat(event.freezeUntil()).isEqualTo(FREEZE_UNTIL);
        assertThat(event.occurredAt()).isEqualTo(FREEZE_AT);
    }

    @Test
    void two_events_with_same_fields_should_be_equal() {
        AccountDeletionRequestedEvent a =
                new AccountDeletionRequestedEvent(ACCOUNT_ID, FREEZE_AT, FREEZE_UNTIL, FREEZE_AT);
        AccountDeletionRequestedEvent b =
                new AccountDeletionRequestedEvent(ACCOUNT_ID, FREEZE_AT, FREEZE_UNTIL, FREEZE_AT);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void toString_should_contain_accountId() {
        AccountDeletionRequestedEvent event =
                new AccountDeletionRequestedEvent(ACCOUNT_ID, FREEZE_AT, FREEZE_UNTIL, FREEZE_AT);

        assertThat(event.toString()).contains("42");
    }
}
