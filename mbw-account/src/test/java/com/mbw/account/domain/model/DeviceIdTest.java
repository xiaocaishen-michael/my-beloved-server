package com.mbw.account.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeviceIdTest {

    private static final String VALID_UUID_V4 = "8a7c1f2e-5b3d-4f6a-9e2c-1d4b5a6c7e8f";

    @Test
    void should_accept_valid_lowercase_uuid_v4() {
        DeviceId id = new DeviceId(VALID_UUID_V4);

        assertThat(id.value()).isEqualTo(VALID_UUID_V4);
    }

    @Test
    void should_reject_uppercase_uuid_to_keep_canonical_form() {
        assertThatThrownBy(() -> new DeviceId("8A7C1F2E-5B3D-4F6A-9E2C-1D4B5A6C7E8F"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_DEVICE_ID");
    }

    @Test
    void should_reject_malformed_value() {
        assertThatThrownBy(() -> new DeviceId("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_DEVICE_ID");
    }

    @Test
    void should_reject_null() {
        assertThatThrownBy(() -> new DeviceId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_fallback_to_random_uuid_when_header_null() {
        DeviceId id = DeviceId.fromHeaderOrFallback(null);

        assertThat(id.value()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    void should_fallback_to_random_uuid_when_header_blank() {
        DeviceId id = DeviceId.fromHeaderOrFallback("   ");

        assertThat(id.value()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    void should_fallback_to_random_uuid_when_header_invalid_format() {
        DeviceId id = DeviceId.fromHeaderOrFallback("garbage-not-a-uuid");

        assertThat(id.value()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    void should_use_header_value_when_valid_uuid_v4() {
        DeviceId id = DeviceId.fromHeaderOrFallback(VALID_UUID_V4);

        assertThat(id.value()).isEqualTo(VALID_UUID_V4);
    }

    @Test
    void should_emit_distinct_uuids_on_repeated_fallback() {
        // Sanity: fallback uses random UUID, not a fixed sentinel.
        DeviceId a = DeviceId.fromHeaderOrFallback(null);
        DeviceId b = DeviceId.fromHeaderOrFallback(null);

        assertThat(a.value()).isNotEqualTo(b.value());
    }
}
