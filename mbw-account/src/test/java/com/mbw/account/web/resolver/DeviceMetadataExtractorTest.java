package com.mbw.account.web.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.account.api.dto.DeviceType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class DeviceMetadataExtractorTest {

    private static final String VALID_UUID_V4 = "8a7c1f2e-5b3d-4f6a-9e2c-1d4b5a6c7e8f";

    // ----- extractIp -----

    @Test
    void should_extract_ipv4_from_xff_first_segment() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1, 192.168.1.1");

        assertThat(DeviceMetadataExtractor.extractIp(req)).isEqualTo("203.0.113.5");
    }

    @Test
    void should_skip_private_ips_in_xff_and_pick_first_public() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "10.0.0.1, 192.168.1.1, 8.8.8.8, 1.1.1.1");

        assertThat(DeviceMetadataExtractor.extractIp(req)).isEqualTo("8.8.8.8");
    }

    @Test
    void should_fallback_to_remoteAddr_when_xff_empty() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.5");

        assertThat(DeviceMetadataExtractor.extractIp(req)).isEqualTo("203.0.113.5");
    }

    @Test
    void should_fallback_to_remoteAddr_when_all_xff_segments_private() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "10.0.0.1, 192.168.1.1");
        req.setRemoteAddr("8.8.8.8");

        assertThat(DeviceMetadataExtractor.extractIp(req)).isEqualTo("8.8.8.8");
    }

    @Test
    void should_return_null_when_all_addresses_private() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "10.0.0.1, 192.168.1.1");
        req.setRemoteAddr("127.0.0.1");

        assertThat(DeviceMetadataExtractor.extractIp(req)).isNull();
    }

    @Test
    void should_return_null_when_xff_segment_malformed_and_remoteAddr_invalid() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "not-an-ip, also-bad");
        req.setRemoteAddr("garbage");

        assertThat(DeviceMetadataExtractor.extractIp(req)).isNull();
    }

    @Test
    void should_trim_whitespace_around_xff_segments() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "  203.0.113.5  ,  10.0.0.1  ");

        assertThat(DeviceMetadataExtractor.extractIp(req)).isEqualTo("203.0.113.5");
    }

    // ----- extractDeviceMetadata -----

    @Test
    void should_use_xDeviceId_when_provided() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Device-Id", VALID_UUID_V4);

        DeviceMetadata md = DeviceMetadataExtractor.extractDeviceMetadata(req);

        assertThat(md.deviceId().value()).isEqualTo(VALID_UUID_V4);
    }

    @Test
    void should_fallback_random_uuid_when_xDeviceId_missing() {
        MockHttpServletRequest req = new MockHttpServletRequest();

        DeviceMetadata md = DeviceMetadataExtractor.extractDeviceMetadata(req);

        assertThat(md.deviceId().value()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    void should_handle_null_xDeviceName_as_null() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Device-Id", VALID_UUID_V4);

        DeviceMetadata md = DeviceMetadataExtractor.extractDeviceMetadata(req);

        assertThat(md.deviceName()).isNull();
    }

    @Test
    void should_wrap_xDeviceName_when_provided() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Device-Id", VALID_UUID_V4);
        req.addHeader("X-Device-Name", "MK-iPhone");

        DeviceMetadata md = DeviceMetadataExtractor.extractDeviceMetadata(req);

        assertThat(md.deviceName()).isNotNull();
        assertThat(md.deviceName().value()).isEqualTo("MK-iPhone");
    }

    @Test
    void should_default_unknown_when_xDeviceType_missing() {
        MockHttpServletRequest req = new MockHttpServletRequest();

        DeviceMetadata md = DeviceMetadataExtractor.extractDeviceMetadata(req);

        assertThat(md.deviceType()).isEqualTo(DeviceType.UNKNOWN);
    }

    @Test
    void should_default_unknown_when_xDeviceType_invalid_value() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Device-Type", "FOO_BAR");

        DeviceMetadata md = DeviceMetadataExtractor.extractDeviceMetadata(req);

        assertThat(md.deviceType()).isEqualTo(DeviceType.UNKNOWN);
    }

    @Test
    void should_parse_xDeviceType_when_recognised() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Device-Type", "PHONE");

        DeviceMetadata md = DeviceMetadataExtractor.extractDeviceMetadata(req);

        assertThat(md.deviceType()).isEqualTo(DeviceType.PHONE);
    }

    @Test
    void should_silently_drop_oversized_xDeviceName_to_null() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Device-Id", VALID_UUID_V4);
        req.addHeader("X-Device-Name", "a".repeat(65)); // exceeds 64-char ceiling

        DeviceMetadata md = DeviceMetadataExtractor.extractDeviceMetadata(req);

        // Bad client header must not crash the request — fold to null and persist.
        assertThat(md.deviceName()).isNull();
    }
}
