package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.api.dto.DeviceType;
import com.mbw.account.api.dto.LoginMethod;
import com.mbw.account.api.event.DeviceRevokedEvent;
import com.mbw.account.application.command.RevokeDeviceCommand;
import com.mbw.account.domain.exception.CannotRemoveCurrentDeviceException;
import com.mbw.account.domain.exception.DeviceNotFoundException;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.DeviceId;
import com.mbw.account.domain.model.IpAddress;
import com.mbw.account.domain.model.RefreshTokenHash;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.model.RefreshTokenRecordId;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.shared.web.RateLimitService;
import com.mbw.shared.web.RateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RevokeDeviceUseCaseTest {

    private static final AccountId ACCOUNT_ID = new AccountId(42L);
    private static final AccountId OTHER_ACCOUNT = new AccountId(99L);
    private static final DeviceId CURRENT_DEVICE = new DeviceId("11111111-1111-4111-8111-111111111111");
    private static final DeviceId TARGET_DEVICE = new DeviceId("22222222-2222-4222-8222-222222222222");
    private static final RefreshTokenRecordId RECORD_ID = new RefreshTokenRecordId(7L);
    private static final String CLIENT_IP = "203.0.113.7";
    private static final RefreshTokenHash HASH =
            new RefreshTokenHash("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    private static final Instant NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private RevokeDeviceUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RevokeDeviceUseCase(rateLimitService, refreshTokenRepository, eventPublisher, CLOCK);
    }

    @Test
    void should_revoke_and_publish_event_when_other_device_active() {
        RefreshTokenRecord active = activeRecord(TARGET_DEVICE, ACCOUNT_ID);
        when(refreshTokenRepository.findById(RECORD_ID)).thenReturn(Optional.of(active));
        when(refreshTokenRepository.revoke(RECORD_ID, NOW)).thenReturn(1);

        useCase.execute(new RevokeDeviceCommand(ACCOUNT_ID, RECORD_ID, CURRENT_DEVICE, CLIENT_IP));

        verify(refreshTokenRepository).revoke(RECORD_ID, NOW);
        ArgumentCaptor<DeviceRevokedEvent> evt = ArgumentCaptor.forClass(DeviceRevokedEvent.class);
        verify(eventPublisher).publishEvent(evt.capture());
        assertThat(evt.getValue().accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(evt.getValue().recordId()).isEqualTo(RECORD_ID);
        assertThat(evt.getValue().deviceId()).isEqualTo(TARGET_DEVICE);
        assertThat(evt.getValue().revokedAt()).isEqualTo(NOW);
    }

    @Test
    void should_treat_zero_affected_rows_as_idempotent_no_event() {
        // Atomic-update race-loser: a concurrent thread already flipped the row.
        RefreshTokenRecord active = activeRecord(TARGET_DEVICE, ACCOUNT_ID);
        when(refreshTokenRepository.findById(RECORD_ID)).thenReturn(Optional.of(active));
        when(refreshTokenRepository.revoke(RECORD_ID, NOW)).thenReturn(0);

        useCase.execute(new RevokeDeviceCommand(ACCOUNT_ID, RECORD_ID, CURRENT_DEVICE, CLIENT_IP));

        verify(refreshTokenRepository).revoke(RECORD_ID, NOW);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_DeviceNotFound_when_recordId_missing() {
        when(refreshTokenRepository.findById(RECORD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                        useCase.execute(new RevokeDeviceCommand(ACCOUNT_ID, RECORD_ID, CURRENT_DEVICE, CLIENT_IP)))
                .isInstanceOf(DeviceNotFoundException.class);
        verify(refreshTokenRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_DeviceNotFound_when_recordId_belongs_to_another_account() {
        RefreshTokenRecord otherAccountRecord = activeRecord(TARGET_DEVICE, OTHER_ACCOUNT);
        when(refreshTokenRepository.findById(RECORD_ID)).thenReturn(Optional.of(otherAccountRecord));

        // Anti-enumeration: byte-identical to the missing-recordId branch.
        assertThatThrownBy(() ->
                        useCase.execute(new RevokeDeviceCommand(ACCOUNT_ID, RECORD_ID, CURRENT_DEVICE, CLIENT_IP)))
                .isInstanceOf(DeviceNotFoundException.class);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void should_throw_CannotRemoveCurrentDevice_when_record_deviceId_equals_current() {
        RefreshTokenRecord selfRecord = activeRecord(CURRENT_DEVICE, ACCOUNT_ID);
        when(refreshTokenRepository.findById(RECORD_ID)).thenReturn(Optional.of(selfRecord));

        assertThatThrownBy(() ->
                        useCase.execute(new RevokeDeviceCommand(ACCOUNT_ID, RECORD_ID, CURRENT_DEVICE, CLIENT_IP)))
                .isInstanceOf(CannotRemoveCurrentDeviceException.class);
        verify(refreshTokenRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_be_idempotent_when_record_already_revoked() {
        RefreshTokenRecord alreadyRevoked =
                activeRecord(TARGET_DEVICE, ACCOUNT_ID).revoke(NOW.minusSeconds(60));
        when(refreshTokenRepository.findById(RECORD_ID)).thenReturn(Optional.of(alreadyRevoked));

        useCase.execute(new RevokeDeviceCommand(ACCOUNT_ID, RECORD_ID, CURRENT_DEVICE, CLIENT_IP));

        verify(refreshTokenRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_RateLimitedException_when_account_throttled() {
        doThrow(new RateLimitedException("device-revoke:account:" + ACCOUNT_ID.value(), Duration.ofSeconds(60)))
                .when(rateLimitService)
                .consumeOrThrow(eq("device-revoke:account:" + ACCOUNT_ID.value()), any());

        assertThatThrownBy(() ->
                        useCase.execute(new RevokeDeviceCommand(ACCOUNT_ID, RECORD_ID, CURRENT_DEVICE, CLIENT_IP)))
                .isInstanceOf(RateLimitedException.class);
        verify(refreshTokenRepository, never()).findById(any());
    }

    @Test
    void should_throw_RateLimitedException_when_ip_throttled() {
        doThrow(new RateLimitedException("device-revoke:ip:" + CLIENT_IP, Duration.ofSeconds(60)))
                .when(rateLimitService)
                .consumeOrThrow(eq("device-revoke:ip:" + CLIENT_IP), any());

        assertThatThrownBy(() ->
                        useCase.execute(new RevokeDeviceCommand(ACCOUNT_ID, RECORD_ID, CURRENT_DEVICE, CLIENT_IP)))
                .isInstanceOf(RateLimitedException.class);
        verify(refreshTokenRepository, never()).findById(any());
    }

    @Test
    void should_skip_ip_rate_limit_when_clientIp_null() {
        RefreshTokenRecord active = activeRecord(TARGET_DEVICE, ACCOUNT_ID);
        when(refreshTokenRepository.findById(RECORD_ID)).thenReturn(Optional.of(active));

        useCase.execute(new RevokeDeviceCommand(ACCOUNT_ID, RECORD_ID, CURRENT_DEVICE, /* clientIp */ null));

        verify(rateLimitService).consumeOrThrow(eq("device-revoke:account:" + ACCOUNT_ID.value()), any());
        verify(rateLimitService, never()).consumeOrThrow(eq("device-revoke:ip:null"), any());
    }

    @Test
    void should_propagate_revoke_failure_so_outer_transaction_rolls_back() {
        RefreshTokenRecord active = activeRecord(TARGET_DEVICE, ACCOUNT_ID);
        when(refreshTokenRepository.findById(RECORD_ID)).thenReturn(Optional.of(active));
        when(refreshTokenRepository.revoke(RECORD_ID, NOW)).thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() ->
                        useCase.execute(new RevokeDeviceCommand(ACCOUNT_ID, RECORD_ID, CURRENT_DEVICE, CLIENT_IP)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB down");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_propagate_publish_failure_so_outer_transaction_rolls_back() {
        RefreshTokenRecord active = activeRecord(TARGET_DEVICE, ACCOUNT_ID);
        when(refreshTokenRepository.findById(RECORD_ID)).thenReturn(Optional.of(active));
        when(refreshTokenRepository.revoke(RECORD_ID, NOW)).thenReturn(1);
        doThrow(new RuntimeException("outbox unavailable"))
                .when(eventPublisher)
                .publishEvent(any(DeviceRevokedEvent.class));

        assertThatThrownBy(() ->
                        useCase.execute(new RevokeDeviceCommand(ACCOUNT_ID, RECORD_ID, CURRENT_DEVICE, CLIENT_IP)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("outbox unavailable");
    }

    private static RefreshTokenRecord activeRecord(DeviceId deviceId, AccountId accountId) {
        return RefreshTokenRecord.reconstitute(
                RECORD_ID,
                HASH,
                accountId,
                deviceId,
                /* deviceName */ null,
                DeviceType.PHONE,
                IpAddress.ofNullable("8.8.8.8"),
                LoginMethod.PHONE_SMS,
                NOW.plusSeconds(3600),
                /* revokedAt */ null,
                NOW.minusSeconds(120));
    }
}
