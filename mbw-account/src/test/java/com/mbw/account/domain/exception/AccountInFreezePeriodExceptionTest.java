package com.mbw.account.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountInFreezePeriodExceptionTest {

    @Test
    @DisplayName("CODE 常量等于 ACCOUNT_IN_FREEZE_PERIOD")
    void codeConstantValue() {
        assertThat(AccountInFreezePeriodException.CODE).isEqualTo("ACCOUNT_IN_FREEZE_PERIOD");
    }

    @Test
    @DisplayName("构造器存 freezeUntil 并由 getter 返回原值")
    void constructorAndGetter() {
        Instant freezeUntil = Instant.parse("2026-05-21T03:00:00Z");

        AccountInFreezePeriodException ex = new AccountInFreezePeriodException(freezeUntil);

        assertThat(ex.getFreezeUntil()).isEqualTo(freezeUntil);
    }

    @Test
    @DisplayName("继承 RuntimeException 以支持 unchecked 抛出")
    void extendsRuntimeException() {
        assertThat(new AccountInFreezePeriodException(Instant.now())).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("Throwable.getMessage() 等于 CODE")
    void messageEqualsCode() {
        assertThat(new AccountInFreezePeriodException(Instant.now()).getMessage())
                .isEqualTo("ACCOUNT_IN_FREEZE_PERIOD");
    }
}
