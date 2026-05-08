package com.mbw.account.infrastructure.geoip;

import com.mbw.account.domain.model.IpAddress;
import com.mbw.account.domain.service.Ip2RegionService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stub {@link Ip2RegionService} that always returns
 * {@link Optional#empty()} (device-management spec T8 placeholder).
 *
 * <p>The real adapter wraps {@code net.dreamlu:mica-ip2region} +
 * a ~5 MB {@code ip2region.xdb} resource and is tracked as the next
 * follow-up PR. Wiring this stub in M1.X keeps the device-list
 * endpoint compilable and the app context bootable; {@code location}
 * fields surface as {@code null} in the meantime, which the client
 * already handles (renders "—" per UI plan).
 *
 * <p>One-line WARN at startup so the absence of GeoIP resolution is
 * visible in production logs and not silently forgotten.
 */
@Component
public class NoOpIp2RegionAdapter implements Ip2RegionService {

    private static final Logger LOG = LoggerFactory.getLogger(NoOpIp2RegionAdapter.class);

    public NoOpIp2RegionAdapter() {
        LOG.warn("ip2region adapter is the no-op stub — device-list location fields will always be null."
                + " Replace with the mica-ip2region implementation per device-management spec T8 follow-up.");
    }

    @Override
    public Optional<String> resolve(IpAddress ip) {
        return Optional.empty();
    }
}
