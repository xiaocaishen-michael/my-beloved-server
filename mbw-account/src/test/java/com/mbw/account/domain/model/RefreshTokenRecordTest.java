package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mbw.account.api.dto.DeviceType;
import com.mbw.account.api.dto.LoginMethod;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefreshTokenRecordTest {

    private static final RefreshTokenHash HASH =
            new RefreshTokenHash("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    private static final AccountId ACCOUNT_ID = new AccountId(42L);
    private static final Instant CREATED_AT = Instant.parse("2026-04-29T01:00:00Z");
    private static final Instant EXPIRES_AT = CREATED_AT.plusSeconds(30L * 24 * 3600); // 30 days
    private static final DeviceId DEVICE_ID = new DeviceId("8a7c1f2e-5b3d-4f6a-9e2c-1d4b5a6c7e8f");
    private static final DeviceName DEVICE_NAME = new DeviceName("MK-iPhone");
    private static final IpAddress IP_ADDRESS = new IpAddress("8.8.8.8");

    @Test
    void should_create_active_record_with_revokedAt_null_and_id_null() {
        RefreshTokenRecord record = RefreshTokenRecord.createActive(HASH, ACCOUNT_ID, EXPIRES_AT, CREATED_AT);

        assertThat(record.id()).isNull();
        assertThat(record.tokenHash()).isEqualTo(HASH);
        assertThat(record.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(record.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(record.revokedAt()).isNull();
        assertThat(record.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void should_set_revokedAt_when_revoke_called() {
        RefreshTokenRecord record = RefreshTokenRecord.createActive(HASH, ACCOUNT_ID, EXPIRES_AT, CREATED_AT);
        Instant revokedAt = CREATED_AT.plusSeconds(60);

        RefreshTokenRecord revoked = record.revoke(revokedAt);

        assertThat(revoked.revokedAt()).isEqualTo(revokedAt);
        // Original instance unchanged (immutability)
        assertThat(record.revokedAt()).isNull();
    }

    @Test
    void should_throw_when_revoke_called_twice() {
        RefreshTokenRecord record = RefreshTokenRecord.createActive(HASH, ACCOUNT_ID, EXPIRES_AT, CREATED_AT);
        RefreshTokenRecord revoked = record.revoke(CREATED_AT.plusSeconds(60));

        assertThatThrownBy(() -> revoked.revoke(CREATED_AT.plusSeconds(120)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Already revoked");
    }

    @Test
    void isActive_should_return_true_when_not_revoked_and_not_expired() {
        RefreshTokenRecord record = RefreshTokenRecord.createActive(HASH, ACCOUNT_ID, EXPIRES_AT, CREATED_AT);

        assertThat(record.isActive(CREATED_AT.plusSeconds(60))).isTrue();
    }

    @Test
    void isActive_should_return_false_when_revoked() {
        RefreshTokenRecord record = RefreshTokenRecord.createActive(HASH, ACCOUNT_ID, EXPIRES_AT, CREATED_AT)
                .revoke(CREATED_AT.plusSeconds(60));

        assertThat(record.isActive(CREATED_AT.plusSeconds(120))).isFalse();
    }

    @Test
    void isActive_should_return_false_when_expired() {
        RefreshTokenRecord record = RefreshTokenRecord.createActive(HASH, ACCOUNT_ID, EXPIRES_AT, CREATED_AT);

        // Now is exactly at expiresAt → isActive(now) requires expiresAt > now → false
        assertThat(record.isActive(EXPIRES_AT)).isFalse();
        assertThat(record.isActive(EXPIRES_AT.plusSeconds(1))).isFalse();
    }

    // ----- Device-management spec FR-007 / T1 — 5 device-metadata fields -----

    @Test
    void should_construct_active_record_with_five_device_fields() {
        RefreshTokenRecord record = RefreshTokenRecord.createActive(
                HASH,
                ACCOUNT_ID,
                DEVICE_ID,
                DEVICE_NAME,
                DeviceType.PHONE,
                IP_ADDRESS,
                LoginMethod.PHONE_SMS,
                EXPIRES_AT,
                CREATED_AT);

        assertThat(record.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(record.deviceName()).isEqualTo(DEVICE_NAME);
        assertThat(record.deviceType()).isEqualTo(DeviceType.PHONE);
        assertThat(record.ipAddress()).isEqualTo(IP_ADDRESS);
        assertThat(record.loginMethod()).isEqualTo(LoginMethod.PHONE_SMS);
    }

    @Test
    void should_throw_when_deviceId_null_in_extended_factory() {
        assertThatThrownBy(() -> RefreshTokenRecord.createActive(
                        HASH,
                        ACCOUNT_ID,
                        /* deviceId */ null,
                        DEVICE_NAME,
                        DeviceType.PHONE,
                        IP_ADDRESS,
                        LoginMethod.PHONE_SMS,
                        EXPIRES_AT,
                        CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("deviceId");
    }

    @Test
    void should_throw_when_deviceType_null_in_extended_factory() {
        assertThatThrownBy(() -> RefreshTokenRecord.createActive(
                        HASH,
                        ACCOUNT_ID,
                        DEVICE_ID,
                        DEVICE_NAME,
                        /* deviceType */ null,
                        IP_ADDRESS,
                        LoginMethod.PHONE_SMS,
                        EXPIRES_AT,
                        CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("deviceType");
    }

    @Test
    void should_throw_when_loginMethod_null_in_extended_factory() {
        assertThatThrownBy(() -> RefreshTokenRecord.createActive(
                        HASH,
                        ACCOUNT_ID,
                        DEVICE_ID,
                        DEVICE_NAME,
                        DeviceType.PHONE,
                        IP_ADDRESS,
                        /* loginMethod */ null,
                        EXPIRES_AT,
                        CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("loginMethod");
    }

    @Test
    void should_allow_null_deviceName_and_ipAddress() {
        RefreshTokenRecord record = RefreshTokenRecord.createActive(
                HASH,
                ACCOUNT_ID,
                DEVICE_ID,
                /* deviceName */ null,
                DeviceType.UNKNOWN,
                /* ipAddress */ null,
                LoginMethod.PHONE_SMS,
                EXPIRES_AT,
                CREATED_AT);

        assertThat(record.deviceName()).isNull();
        assertThat(record.ipAddress()).isNull();
        assertThat(record.deviceId()).isEqualTo(DEVICE_ID);
    }

    @Test
    void should_preserve_five_device_fields_after_revoke() {
        RefreshTokenRecord active = RefreshTokenRecord.createActive(
                HASH,
                ACCOUNT_ID,
                DEVICE_ID,
                DEVICE_NAME,
                DeviceType.PHONE,
                IP_ADDRESS,
                LoginMethod.PHONE_SMS,
                EXPIRES_AT,
                CREATED_AT);

        RefreshTokenRecord revoked = active.revoke(CREATED_AT.plusSeconds(60));

        assertThat(revoked.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(revoked.deviceName()).isEqualTo(DEVICE_NAME);
        assertThat(revoked.deviceType()).isEqualTo(DeviceType.PHONE);
        assertThat(revoked.ipAddress()).isEqualTo(IP_ADDRESS);
        assertThat(revoked.loginMethod()).isEqualTo(LoginMethod.PHONE_SMS);
    }

    @Test
    void should_reconstitute_with_all_fields() {
        RefreshTokenRecordId id = new RefreshTokenRecordId(99L);
        Instant revokedAt = CREATED_AT.plusSeconds(60);

        RefreshTokenRecord record = RefreshTokenRecord.reconstitute(
                id,
                HASH,
                ACCOUNT_ID,
                DEVICE_ID,
                DEVICE_NAME,
                DeviceType.TABLET,
                IP_ADDRESS,
                LoginMethod.GOOGLE,
                EXPIRES_AT,
                revokedAt,
                CREATED_AT);

        assertThat(record.id()).isEqualTo(id);
        assertThat(record.tokenHash()).isEqualTo(HASH);
        assertThat(record.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(record.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(record.deviceName()).isEqualTo(DEVICE_NAME);
        assertThat(record.deviceType()).isEqualTo(DeviceType.TABLET);
        assertThat(record.ipAddress()).isEqualTo(IP_ADDRESS);
        assertThat(record.loginMethod()).isEqualTo(LoginMethod.GOOGLE);
        assertThat(record.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(record.revokedAt()).isEqualTo(revokedAt);
        assertThat(record.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void deprecated_factory_should_default_to_unknown_phoneSms_random_uuid_for_legacy_callers() {
        // Backward-compat path used by 4 existing token-issuing UseCases until T9 wiring.
        RefreshTokenRecord record = RefreshTokenRecord.createActive(HASH, ACCOUNT_ID, EXPIRES_AT, CREATED_AT);

        assertThat(record.deviceId()).isNotNull();
        assertThat(record.deviceId().value()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(record.deviceName()).isNull();
        assertThat(record.deviceType()).isEqualTo(DeviceType.UNKNOWN);
        assertThat(record.ipAddress()).isNull();
        assertThat(record.loginMethod()).isEqualTo(LoginMethod.PHONE_SMS);
    }
}
