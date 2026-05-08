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
import com.mbw.account.application.query.ListDevicesQuery;
import com.mbw.account.application.result.DeviceItem;
import com.mbw.account.application.result.DeviceListResult;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.DeviceId;
import com.mbw.account.domain.model.DeviceName;
import com.mbw.account.domain.model.IpAddress;
import com.mbw.account.domain.model.RefreshTokenHash;
import com.mbw.account.domain.model.RefreshTokenPage;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.model.RefreshTokenRecordId;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.account.domain.service.Ip2RegionService;
import com.mbw.shared.web.RateLimitService;
import com.mbw.shared.web.RateLimitedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ListDevicesUseCaseTest {

    private static final AccountId ACCOUNT_ID = new AccountId(42L);
    private static final DeviceId CURRENT_DEVICE = new DeviceId("11111111-1111-4111-8111-111111111111");
    private static final DeviceId OTHER_DEVICE_A = new DeviceId("22222222-2222-4222-8222-222222222222");
    private static final DeviceId OTHER_DEVICE_B = new DeviceId("33333333-3333-4333-8333-333333333333");
    private static final String CLIENT_IP = "203.0.113.7";
    private static final RefreshTokenHash HASH =
            new RefreshTokenHash("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    private static final Instant NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(30L * 24 * 3600);

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private Ip2RegionService ip2RegionService;

    private ListDevicesUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ListDevicesUseCase(rateLimitService, refreshTokenRepository, ip2RegionService);
    }

    @Test
    void should_return_paginated_items_with_isCurrent_flag() {
        RefreshTokenRecord r1 = active(1L, OTHER_DEVICE_A, "MK-iPhone", DeviceType.PHONE, "8.8.8.8");
        RefreshTokenRecord r2 = active(2L, CURRENT_DEVICE, "MK-iPad", DeviceType.TABLET, "1.1.1.1");
        RefreshTokenRecord r3 = active(3L, OTHER_DEVICE_B, "MacBook", DeviceType.DESKTOP, "203.0.113.5");
        when(refreshTokenRepository.findActiveByAccountId(ACCOUNT_ID, 0, 10))
                .thenReturn(new RefreshTokenPage(List.of(r1, r2, r3), 3));
        when(ip2RegionService.resolve(any())).thenReturn(Optional.of("上海"));

        DeviceListResult result = useCase.execute(new ListDevicesQuery(ACCOUNT_ID, CURRENT_DEVICE, 0, 10, CLIENT_IP));

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.items()).hasSize(3);
        assertThat(result.items()).extracting(DeviceItem::isCurrent).containsExactly(false, true, false);
        assertThat(result.items()).extracting(DeviceItem::id).containsExactly(1L, 2L, 3L);
    }

    @Test
    void should_clamp_size_to_100_when_request_size_over_100() {
        when(refreshTokenRepository.findActiveByAccountId(ACCOUNT_ID, 0, 100))
                .thenReturn(new RefreshTokenPage(List.of(), 0));

        DeviceListResult result = useCase.execute(new ListDevicesQuery(ACCOUNT_ID, CURRENT_DEVICE, 0, 500, CLIENT_IP));

        assertThat(result.size()).isEqualTo(100);
        verify(refreshTokenRepository).findActiveByAccountId(ACCOUNT_ID, 0, 100);
    }

    @Test
    void should_pass_through_size_when_at_or_below_100() {
        when(refreshTokenRepository.findActiveByAccountId(ACCOUNT_ID, 0, 25))
                .thenReturn(new RefreshTokenPage(List.of(), 0));

        useCase.execute(new ListDevicesQuery(ACCOUNT_ID, CURRENT_DEVICE, 0, 25, CLIENT_IP));

        verify(refreshTokenRepository).findActiveByAccountId(ACCOUNT_ID, 0, 25);
    }

    @Test
    void should_resolve_location_via_ip2region_for_each_item_with_ip() {
        RefreshTokenRecord r1 = active(1L, OTHER_DEVICE_A, "MK-iPhone", DeviceType.PHONE, "8.8.8.8");
        when(refreshTokenRepository.findActiveByAccountId(ACCOUNT_ID, 0, 10))
                .thenReturn(new RefreshTokenPage(List.of(r1), 1));
        when(ip2RegionService.resolve(any(IpAddress.class))).thenReturn(Optional.of("北京"));

        DeviceListResult result = useCase.execute(new ListDevicesQuery(ACCOUNT_ID, CURRENT_DEVICE, 0, 10, CLIENT_IP));

        assertThat(result.items().get(0).location()).isEqualTo("北京");
    }

    @Test
    void should_set_location_null_when_ip2region_returns_empty() {
        RefreshTokenRecord r1 = active(1L, OTHER_DEVICE_A, "MK-iPhone", DeviceType.PHONE, "8.8.8.8");
        when(refreshTokenRepository.findActiveByAccountId(ACCOUNT_ID, 0, 10))
                .thenReturn(new RefreshTokenPage(List.of(r1), 1));
        when(ip2RegionService.resolve(any(IpAddress.class))).thenReturn(Optional.empty());

        DeviceListResult result = useCase.execute(new ListDevicesQuery(ACCOUNT_ID, CURRENT_DEVICE, 0, 10, CLIENT_IP));

        assertThat(result.items().get(0).location()).isNull();
    }

    @Test
    void should_set_location_null_when_record_ip_is_null_without_calling_ip2region() {
        RefreshTokenRecord r1 = activeNoIp(1L, OTHER_DEVICE_A);
        when(refreshTokenRepository.findActiveByAccountId(ACCOUNT_ID, 0, 10))
                .thenReturn(new RefreshTokenPage(List.of(r1), 1));

        DeviceListResult result = useCase.execute(new ListDevicesQuery(ACCOUNT_ID, CURRENT_DEVICE, 0, 10, CLIENT_IP));

        assertThat(result.items().get(0).location()).isNull();
        verify(ip2RegionService, never()).resolve(any());
    }

    @Test
    void should_throw_RateLimitedException_when_account_throttled() {
        doThrow(new RateLimitedException("device-list:account:" + ACCOUNT_ID.value(), Duration.ofSeconds(60)))
                .when(rateLimitService)
                .consumeOrThrow(eq("device-list:account:" + ACCOUNT_ID.value()), any());

        assertThatThrownBy(() -> useCase.execute(new ListDevicesQuery(ACCOUNT_ID, CURRENT_DEVICE, 0, 10, CLIENT_IP)))
                .isInstanceOf(RateLimitedException.class);

        verify(refreshTokenRepository, never()).findActiveByAccountId(any(), anyIntZero(), anyIntZero());
    }

    @Test
    void should_throw_RateLimitedException_when_ip_throttled() {
        doThrow(new RateLimitedException("device-list:ip:" + CLIENT_IP, Duration.ofSeconds(60)))
                .when(rateLimitService)
                .consumeOrThrow(eq("device-list:ip:" + CLIENT_IP), any());

        assertThatThrownBy(() -> useCase.execute(new ListDevicesQuery(ACCOUNT_ID, CURRENT_DEVICE, 0, 10, CLIENT_IP)))
                .isInstanceOf(RateLimitedException.class);

        verify(refreshTokenRepository, never()).findActiveByAccountId(any(), anyIntZero(), anyIntZero());
    }

    @Test
    void should_skip_ip_rate_limit_when_clientIp_null() {
        when(refreshTokenRepository.findActiveByAccountId(ACCOUNT_ID, 0, 10))
                .thenReturn(new RefreshTokenPage(List.of(), 0));

        useCase.execute(new ListDevicesQuery(ACCOUNT_ID, CURRENT_DEVICE, 0, 10, /* clientIp */ null));

        verify(rateLimitService).consumeOrThrow(eq("device-list:account:" + ACCOUNT_ID.value()), any());
        verify(rateLimitService, never()).consumeOrThrow(eq("device-list:ip:null"), any());
    }

    @Test
    void should_return_empty_items_when_no_active_records() {
        when(refreshTokenRepository.findActiveByAccountId(ACCOUNT_ID, 0, 10))
                .thenReturn(new RefreshTokenPage(List.of(), 0));

        DeviceListResult result = useCase.execute(new ListDevicesQuery(ACCOUNT_ID, CURRENT_DEVICE, 0, 10, CLIENT_IP));

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    void should_compute_totalPages_via_ceiling_division() {
        when(refreshTokenRepository.findActiveByAccountId(ACCOUNT_ID, 0, 10))
                .thenReturn(new RefreshTokenPage(List.of(), 12));

        DeviceListResult result = useCase.execute(new ListDevicesQuery(ACCOUNT_ID, CURRENT_DEVICE, 0, 10, CLIENT_IP));

        assertThat(result.totalPages()).isEqualTo(2);
    }

    private static int anyIntZero() {
        // Mockito.anyInt is verbose at the call sites; this static helper is purely for grep-friendly intent.
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private static RefreshTokenRecord active(
            long id, DeviceId deviceId, String deviceName, DeviceType type, String ip) {
        return RefreshTokenRecord.reconstitute(
                new RefreshTokenRecordId(id),
                HASH,
                ACCOUNT_ID,
                deviceId,
                DeviceName.ofNullable(deviceName),
                type,
                IpAddress.ofNullable(ip),
                LoginMethod.PHONE_SMS,
                EXPIRES,
                /* revokedAt */ null,
                NOW);
    }

    private static RefreshTokenRecord activeNoIp(long id, DeviceId deviceId) {
        return RefreshTokenRecord.reconstitute(
                new RefreshTokenRecordId(id),
                HASH,
                ACCOUNT_ID,
                deviceId,
                /* deviceName */ null,
                DeviceType.UNKNOWN,
                /* ipAddress */ null,
                LoginMethod.PHONE_SMS,
                EXPIRES,
                /* revokedAt */ null,
                NOW);
    }
}
