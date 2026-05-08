package com.mbw.account.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mbw.account.application.config.RealnamePepperProperties;
import org.junit.jupiter.api.Test;

class IdentityHashServiceTest {

    private static final String SAMPLE_ID = "11010119900101001X";

    @Test
    void sha256Hex_should_be_deterministic_for_same_input_and_pepper() {
        IdentityHashService a = new IdentityHashService(new RealnamePepperProperties("pepper-1"));
        IdentityHashService b = new IdentityHashService(new RealnamePepperProperties("pepper-1"));

        assertThat(a.sha256Hex(SAMPLE_ID)).isEqualTo(b.sha256Hex(SAMPLE_ID));
    }

    @Test
    void sha256Hex_should_differ_when_pepper_differs() {
        IdentityHashService a = new IdentityHashService(new RealnamePepperProperties("pepper-1"));
        IdentityHashService b = new IdentityHashService(new RealnamePepperProperties("pepper-2"));

        assertThat(a.sha256Hex(SAMPLE_ID)).isNotEqualTo(b.sha256Hex(SAMPLE_ID));
    }

    @Test
    void sha256Hex_should_produce_64_char_lowercase_hex() {
        IdentityHashService service = new IdentityHashService(new RealnamePepperProperties("p"));

        String hash = service.sha256Hex(SAMPLE_ID);

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void constructor_should_throw_when_pepper_is_blank() {
        assertThatThrownBy(() -> new IdentityHashService(new RealnamePepperProperties("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MBW_REALNAME_PEPPER");
    }

    @Test
    void sha256Hex_should_throw_when_idCardNo_is_blank() {
        IdentityHashService service = new IdentityHashService(new RealnamePepperProperties("p"));

        assertThatThrownBy(() -> service.sha256Hex(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idCardNo");
    }
}
