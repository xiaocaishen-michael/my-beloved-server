package com.mbw.app.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.app.infrastructure.email.ResendEmailClient;
import com.mbw.app.infrastructure.sms.AliyunSmsClient;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Spring 4.3+ "single public constructor inference" 只对**唯一**构造器
 * 生效。当 {@code @Component} 类声明多个构造器时（典型：production ctor
 * + package-private test ctor），必须在 production ctor 上加
 * {@code @Autowired} 显式标注，否则 Spring 退回找 no-arg ctor，找不到
 * 即 {@code BeanInstantiationException: No default constructor found}
 * 在启动时抛出。
 *
 * <p>2026-05-01 prod 撞此 bug — {@code ResendEmailClient} 在 SWAS 第一次
 * 真实例化（之前 IT 都是直接 {@code new}，盲区）启动 fail。修法：给主
 * 构造器加 {@code @Autowired}。
 *
 * <p>本测试守护所有"多构造器 @Component"的基础设施类必须恰好有 1 个
 * {@code @Autowired} 构造器。新增同模式类时把它加到下面 verifier 列表。
 */
class MultiConstructorComponentInjectionTest {

    @Test
    @DisplayName("ResendEmailClient: 多构造器（prod + test）+ 唯一 @Autowired")
    void resend_email_client() {
        assertExactlyOneAutowiredConstructor(ResendEmailClient.class);
    }

    @Test
    @DisplayName("AliyunSmsClient: 多构造器（prod + test）+ 唯一 @Autowired")
    void aliyun_sms_client() {
        assertExactlyOneAutowiredConstructor(AliyunSmsClient.class);
    }

    private static void assertExactlyOneAutowiredConstructor(Class<?> clazz) {
        Constructor<?>[] ctors = clazz.getDeclaredConstructors();
        if (ctors.length <= 1) {
            return; // 单构造器 — Spring auto-picks，无需 @Autowired
        }
        long autowired = Arrays.stream(ctors)
                .filter(c -> c.isAnnotationPresent(Autowired.class))
                .count();
        assertThat(autowired)
                .as(
                        "%s declares %d constructors; Spring requires exactly one annotated with @Autowired"
                                + " (none found → BeanInstantiationException: No default constructor found at startup)",
                        clazz.getSimpleName(), ctors.length)
                .isEqualTo(1);
    }
}
