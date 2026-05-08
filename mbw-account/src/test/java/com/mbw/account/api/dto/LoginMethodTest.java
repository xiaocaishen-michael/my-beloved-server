package com.mbw.account.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoginMethodTest {

    @Test
    void should_define_four_login_methods_per_FR_007_no_REFRESH() {
        // Per FR-012: REFRESH 不入 enum (refresh path inherits parent row's value).
        assertThat(LoginMethod.values())
                .containsExactly(LoginMethod.PHONE_SMS, LoginMethod.GOOGLE, LoginMethod.APPLE, LoginMethod.WECHAT);
    }

    @Test
    void should_be_resolvable_from_uppercase_string() {
        assertThat(LoginMethod.valueOf("PHONE_SMS")).isEqualTo(LoginMethod.PHONE_SMS);
        assertThat(LoginMethod.valueOf("GOOGLE")).isEqualTo(LoginMethod.GOOGLE);
        assertThat(LoginMethod.valueOf("APPLE")).isEqualTo(LoginMethod.APPLE);
        assertThat(LoginMethod.valueOf("WECHAT")).isEqualTo(LoginMethod.WECHAT);
    }
}
