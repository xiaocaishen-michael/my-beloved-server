package com.mbw.account.infrastructure.geoip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mbw.account.domain.model.IpAddress;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle + lookup IT for {@link Ip2RegionAdapter} (device-management
 * spec T8). Loads the bundled v4 .xdb directly from the classpath
 * (no Spring context needed) and exercises the full
 * {@code resolve → parseRegion → label} pipeline against fixed China /
 * non-China / private / IPv6 inputs.
 *
 * <p>Assertions are deliberately loose on the exact Chinese label so a
 * future xdb refresh that re-balances district boundaries doesn't break
 * the test — the contract is "non-empty Chinese label for known China
 * IPs, empty for anything else".
 */
class Ip2RegionAdapterIT {

    private Ip2RegionAdapter adapter;

    @BeforeEach
    void load() throws IOException {
        adapter = new Ip2RegionAdapter("geoip/ip2region.xdb");
        adapter.load();
    }

    @AfterEach
    void close() throws IOException {
        adapter.close();
    }

    @Test
    void should_resolve_known_china_ip_to_non_empty_label() {
        // 124.88.135.1 — 北京联通; well-known stable range
        Optional<String> region = adapter.resolve(new IpAddress("124.88.135.1"));

        assertThat(region).isPresent();
        assertThat(region.get()).isNotBlank();
    }

    @Test
    void should_resolve_shanghai_range() {
        // 116.224.0.1 — 上海 ranges
        Optional<String> region = adapter.resolve(new IpAddress("116.224.0.1"));

        assertThat(region).isPresent();
        assertThat(region.get()).contains("上海");
    }

    @Test
    void should_resolve_shenzhen_range() {
        // 113.108.81.1 — 广东深圳 China Telecom
        Optional<String> region = adapter.resolve(new IpAddress("113.108.81.1"));

        assertThat(region).isPresent();
        // Either "深圳" or "广东深圳" depending on whether province == city.
        assertThat(region.get()).containsAnyOf("深圳", "广东");
    }

    @Test
    void should_resolve_hangzhou_range() {
        // 122.224.0.1 — 浙江杭州 China Telecom
        Optional<String> region = adapter.resolve(new IpAddress("122.224.0.1"));

        assertThat(region).isPresent();
        assertThat(region.get()).containsAnyOf("杭州", "浙江");
    }

    @Test
    void should_resolve_guangzhou_range() {
        // 219.137.135.1 — 广东广州 China Telecom
        Optional<String> region = adapter.resolve(new IpAddress("219.137.135.1"));

        assertThat(region).isPresent();
        assertThat(region.get()).containsAnyOf("广州", "广东");
    }

    @Test
    void should_return_empty_for_us_ip() {
        // 8.8.8.8 — Google public DNS in the US, not in 中国 mainland.
        Optional<String> region = adapter.resolve(new IpAddress("8.8.8.8"));

        assertThat(region).isEmpty();
    }

    @Test
    void should_return_empty_for_private_10x_ip() {
        Optional<String> region = adapter.resolve(new IpAddress("10.0.0.1"));

        assertThat(region).isEmpty();
    }

    @Test
    void should_return_empty_for_loopback_127() {
        Optional<String> region = adapter.resolve(new IpAddress("127.0.0.1"));

        assertThat(region).isEmpty();
    }

    @Test
    void should_return_empty_for_null_input() {
        Optional<String> region = adapter.resolve(null);

        assertThat(region).isEmpty();
    }

    @Test
    void should_return_empty_for_ipv6_against_v4_only_db() {
        // The shipped .xdb is the v4 build; IPv6 must fold to empty rather
        // than throw, so the device-list endpoint stays usable.
        Optional<String> region = adapter.resolve(new IpAddress("2001:4860:4860::8888"));

        assertThat(region).isEmpty();
    }

    @Test
    void load_should_throw_when_xdb_resource_missing() {
        Ip2RegionAdapter broken = new Ip2RegionAdapter("geoip/no-such.xdb");

        assertThatThrownBy(broken::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }
}
