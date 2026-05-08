package com.mbw.account.domain.service;

import com.mbw.account.domain.model.IpAddress;
import java.util.Optional;

/**
 * Domain abstraction for offline IP-to-region resolution
 * (device-management spec FR-011).
 *
 * <p>{@link #resolve} returns a Chinese province/city label such as
 * "上海" or "广东深圳" when the IP can be located, or {@link
 * Optional#empty()} for any address that cannot be (or should not be)
 * resolved — null input, private/loopback ranges, or lookup failure.
 *
 * <p>The implementation is expected to be in-process and lock-free
 * (e.g. mica-ip2region's vector index pre-loaded at startup) so the
 * device-list endpoint stays under SC-001 P95 ≤ 200ms even when
 * mapping ten rows in a single request.
 */
public interface Ip2RegionService {

    /** Resolve an IP literal to a coarse region label, or empty if unresolvable / private / null. */
    Optional<String> resolve(IpAddress ip);
}
