package com.mbw.account.api.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.DeviceId;
import com.mbw.account.domain.model.RefreshTokenRecordId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeviceRevokedEventTest {

    @Test
    void should_construct_record_with_all_five_fields() {
        AccountId accountId = new AccountId(7L);
        RefreshTokenRecordId recordId = new RefreshTokenRecordId(99L);
        DeviceId deviceId = new DeviceId("8a7c1f2e-5b3d-4f6a-9e2c-1d4b5a6c7e8f");
        Instant revokedAt = Instant.parse("2026-05-08T10:30:00Z");

        DeviceRevokedEvent event = new DeviceRevokedEvent(accountId, recordId, deviceId, revokedAt, revokedAt);

        assertThat(event.accountId()).isEqualTo(accountId);
        assertThat(event.recordId()).isEqualTo(recordId);
        assertThat(event.deviceId()).isEqualTo(deviceId);
        assertThat(event.revokedAt()).isEqualTo(revokedAt);
        assertThat(event.occurredAt()).isEqualTo(revokedAt);
    }
}
