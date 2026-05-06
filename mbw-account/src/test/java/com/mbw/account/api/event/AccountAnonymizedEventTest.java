package com.mbw.account.api.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.account.domain.model.AccountId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccountAnonymizedEventTest {

    private static final AccountId ACCOUNT_ID = new AccountId(42L);
    private static final Instant ANONYMIZED_AT = Instant.parse("2026-05-21T03:00:00Z");

    @Test
    void fields_should_be_accessible_via_record_accessors() {
        AccountAnonymizedEvent event = new AccountAnonymizedEvent(ACCOUNT_ID, ANONYMIZED_AT, ANONYMIZED_AT);

        assertThat(event.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(event.anonymizedAt()).isEqualTo(ANONYMIZED_AT);
        assertThat(event.occurredAt()).isEqualTo(ANONYMIZED_AT);
    }

    @Test
    void two_events_with_same_fields_should_be_equal() {
        AccountAnonymizedEvent a = new AccountAnonymizedEvent(ACCOUNT_ID, ANONYMIZED_AT, ANONYMIZED_AT);
        AccountAnonymizedEvent b = new AccountAnonymizedEvent(ACCOUNT_ID, ANONYMIZED_AT, ANONYMIZED_AT);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void toString_should_contain_accountId() {
        AccountAnonymizedEvent event = new AccountAnonymizedEvent(ACCOUNT_ID, ANONYMIZED_AT, ANONYMIZED_AT);

        assertThat(event.toString()).contains("42");
    }
}
