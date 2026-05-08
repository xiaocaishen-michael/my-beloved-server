package com.mbw.account.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeviceTypeTest {

    @Test
    void should_define_five_device_types_per_FR_007() {
        assertThat(DeviceType.values())
                .containsExactly(
                        DeviceType.PHONE, DeviceType.TABLET, DeviceType.DESKTOP, DeviceType.WEB, DeviceType.UNKNOWN);
    }

    @Test
    void should_be_resolvable_from_uppercase_string() {
        assertThat(DeviceType.valueOf("PHONE")).isEqualTo(DeviceType.PHONE);
        assertThat(DeviceType.valueOf("TABLET")).isEqualTo(DeviceType.TABLET);
        assertThat(DeviceType.valueOf("DESKTOP")).isEqualTo(DeviceType.DESKTOP);
        assertThat(DeviceType.valueOf("WEB")).isEqualTo(DeviceType.WEB);
        assertThat(DeviceType.valueOf("UNKNOWN")).isEqualTo(DeviceType.UNKNOWN);
    }
}
