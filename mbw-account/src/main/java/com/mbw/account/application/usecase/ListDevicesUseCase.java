package com.mbw.account.application.usecase;

import com.mbw.account.application.query.ListDevicesQuery;
import com.mbw.account.application.result.DeviceItem;
import com.mbw.account.application.result.DeviceListResult;
import com.mbw.account.domain.model.DeviceName;
import com.mbw.account.domain.model.IpAddress;
import com.mbw.account.domain.model.RefreshTokenPage;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.account.domain.service.Ip2RegionService;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * List the active devices of the authenticated account
 * (device-management spec FR-001 / FR-002 / FR-013).
 *
 * <p>Per-account rate limit (30 req/min) and per-IP rate limit
 * (100 req/min) are applied first; the IP bucket is skipped when
 * {@code clientIp} is {@code null} (request from inside the trust
 * boundary). The repo page is then mapped 1-to-1 into
 * {@link DeviceItem} rows, with the {@code did} claim of the caller's
 * access token compared per-row to set {@code isCurrent} (FR-004) and
 * {@link Ip2RegionService} resolving the row's IP into a Chinese
 * province/city label (FR-011). Rows without an IP, or whose IP cannot
 * be resolved, surface {@code location = null}.
 */
@Service
public class ListDevicesUseCase {

    static final String ACCOUNT_RATE_LIMIT_KEY_PREFIX = "device-list:account:";
    static final String IP_RATE_LIMIT_KEY_PREFIX = "device-list:ip:";
    static final int MAX_PAGE_SIZE = 100;

    static final Bandwidth DEVICE_LIST_PER_ACCOUNT_60S = Bandwidth.builder()
            .capacity(30)
            .refillIntervally(30, Duration.ofSeconds(60))
            .build();
    static final Bandwidth DEVICE_LIST_PER_IP_60S = Bandwidth.builder()
            .capacity(100)
            .refillIntervally(100, Duration.ofSeconds(60))
            .build();

    private final RateLimitService rateLimitService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Ip2RegionService ip2RegionService;

    public ListDevicesUseCase(
            RateLimitService rateLimitService,
            RefreshTokenRepository refreshTokenRepository,
            Ip2RegionService ip2RegionService) {
        this.rateLimitService = rateLimitService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.ip2RegionService = ip2RegionService;
    }

    public DeviceListResult execute(ListDevicesQuery query) {
        rateLimitService.consumeOrThrow(
                ACCOUNT_RATE_LIMIT_KEY_PREFIX + query.accountId().value(), DEVICE_LIST_PER_ACCOUNT_60S);
        if (query.clientIp() != null) {
            rateLimitService.consumeOrThrow(IP_RATE_LIMIT_KEY_PREFIX + query.clientIp(), DEVICE_LIST_PER_IP_60S);
        }

        int clampedSize = Math.min(query.size(), MAX_PAGE_SIZE);
        RefreshTokenPage page =
                refreshTokenRepository.findActiveByAccountId(query.accountId(), query.page(), clampedSize);

        List<DeviceItem> items =
                page.items().stream().map(record -> toDeviceItem(record, query)).toList();

        int totalPages = page.totalElements() == 0L
                ? 0
                : Math.toIntExact((page.totalElements() + clampedSize - 1) / clampedSize);

        return new DeviceListResult(query.page(), clampedSize, page.totalElements(), totalPages, items);
    }

    private DeviceItem toDeviceItem(RefreshTokenRecord record, ListDevicesQuery query) {
        IpAddress ip = record.ipAddress();
        String location = ip == null ? null : ip2RegionService.resolve(ip).orElse(null);
        DeviceName name = record.deviceName();
        boolean isCurrent = record.deviceId().equals(query.currentDeviceId());
        return new DeviceItem(
                record.id().value(),
                record.deviceId().value(),
                name == null ? null : name.value(),
                record.deviceType(),
                location,
                record.loginMethod(),
                record.createdAt(),
                isCurrent);
    }
}
