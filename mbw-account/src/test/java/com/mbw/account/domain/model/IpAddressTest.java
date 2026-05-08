package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IpAddressTest {

    @Test
    void should_accept_ipv4_public() {
        IpAddress ip = new IpAddress("8.8.8.8");

        assertThat(ip.value()).isEqualTo("8.8.8.8");
        assertThat(ip.isPrivate()).isFalse();
    }

    @Test
    void should_accept_ipv6_public() {
        IpAddress ip = new IpAddress("2001:4860:4860::8888");

        assertThat(ip.value()).isEqualTo("2001:4860:4860::8888");
        assertThat(ip.isPrivate()).isFalse();
    }

    @Test
    void should_detect_private_10x_block() {
        assertThat(new IpAddress("10.0.0.1").isPrivate()).isTrue();
        assertThat(new IpAddress("10.255.255.254").isPrivate()).isTrue();
    }

    @Test
    void should_detect_private_172_16_through_31_block() {
        assertThat(new IpAddress("172.16.0.1").isPrivate()).isTrue();
        assertThat(new IpAddress("172.31.255.254").isPrivate()).isTrue();
        // 172.32.x is not private
        assertThat(new IpAddress("172.32.0.1").isPrivate()).isFalse();
        // 172.15.x is not private
        assertThat(new IpAddress("172.15.255.254").isPrivate()).isFalse();
    }

    @Test
    void should_detect_private_192_168_block() {
        assertThat(new IpAddress("192.168.1.1").isPrivate()).isTrue();
    }

    @Test
    void should_detect_ipv4_loopback() {
        assertThat(new IpAddress("127.0.0.1").isPrivate()).isTrue();
    }

    @Test
    void should_detect_ipv4_link_local_169_254() {
        assertThat(new IpAddress("169.254.1.1").isPrivate()).isTrue();
    }

    @Test
    void should_detect_ipv6_loopback() {
        assertThat(new IpAddress("::1").isPrivate()).isTrue();
    }

    @Test
    void should_detect_ipv6_link_local_fe80() {
        assertThat(new IpAddress("fe80::1").isPrivate()).isTrue();
    }

    @Test
    void should_detect_ipv6_unique_local_fc00_block() {
        // RFC 4193 ULA — Java's InetAddress.isSiteLocalAddress only covers
        // deprecated fec0::/10 and misses fc00::/7, so we check explicitly.
        assertThat(new IpAddress("fc00::1").isPrivate()).isTrue();
        assertThat(new IpAddress("fdff:abcd:1234::1").isPrivate()).isTrue();
    }

    @Test
    void should_reject_malformed_value() {
        assertThatThrownBy(() -> new IpAddress("not-an-ip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_IP_ADDRESS");
    }

    @Test
    void should_reject_hostname_to_avoid_dns_lookup() {
        // Hostnames must be rejected — they would otherwise trigger DNS
        // resolution in InetAddress.getByName.
        assertThatThrownBy(() -> new IpAddress("example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_IP_ADDRESS");
    }

    @Test
    void should_reject_ipv4_with_out_of_range_octet() {
        assertThatThrownBy(() -> new IpAddress("256.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_IP_ADDRESS");
    }

    @Test
    void should_reject_null() {
        assertThatThrownBy(() -> new IpAddress(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofNullable_should_return_null_when_input_null() {
        assertThat(IpAddress.ofNullable(null)).isNull();
    }

    @Test
    void ofNullable_should_return_null_when_input_blank() {
        assertThat(IpAddress.ofNullable("   ")).isNull();
    }

    @Test
    void ofNullable_should_return_null_when_input_invalid_format() {
        // Permissive degradation for HTTP header path: bad value → null,
        // not an exception (invalid IP shouldn't crash the request).
        assertThat(IpAddress.ofNullable("garbage")).isNull();
    }

    @Test
    void ofNullable_should_wrap_valid_input() {
        IpAddress ip = IpAddress.ofNullable("8.8.8.8");

        assertThat(ip).isNotNull();
        assertThat(ip.value()).isEqualTo("8.8.8.8");
    }
}
