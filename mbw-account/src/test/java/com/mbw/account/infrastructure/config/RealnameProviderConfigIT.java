package com.mbw.account.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.account.infrastructure.client.AliyunRealnameClient;
import com.mbw.account.infrastructure.client.AliyunRealnameClientConfig;
import com.mbw.account.infrastructure.client.BypassRealnameClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * IT for the realname-verification provider routing + prod fail-fast
 * (realname-verification spec T11).
 *
 * <p>Boots a slim Spring context containing only the provider stack
 * (BypassRealnameClient + AliyunRealnameClient + their configs +
 * RealnameProviderConfig validator) under five profile / property
 * combinations and asserts which beans are wired or that startup is
 * rejected outright.
 */
class RealnameProviderConfigIT {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(RealnameTestContext.class);

    @Test
    void dev_profile_with_dev_bypass_true_should_wire_BypassRealnameClient() {
        contextRunner
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues("mbw.realname.dev-bypass=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(BypassRealnameClient.class);
                    assertThat(context).doesNotHaveBean(AliyunRealnameClient.class);
                });
    }

    @Test
    void dev_profile_with_dev_bypass_false_should_wire_AliyunRealnameClient() {
        contextRunner
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues(
                        "mbw.realname.dev-bypass=false",
                        "mbw.realname.aliyun.access-key-id=ak",
                        "mbw.realname.aliyun.access-key-secret=secret",
                        "mbw.realname.aliyun.scene-id=100200")
                .run(context -> {
                    assertThat(context).hasSingleBean(AliyunRealnameClient.class);
                    assertThat(context).doesNotHaveBean(BypassRealnameClient.class);
                });
    }

    @Test
    void prod_profile_with_dev_bypass_true_should_fail_startup() {
        contextRunner
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("mbw.realname.dev-bypass=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause().hasMessageContaining("dev-bypass=true is forbidden");
                });
    }

    @Test
    void prod_profile_missing_dek_should_fail_startup() {
        contextRunner
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues(
                        "mbw.realname.dev-bypass=false",
                        "mbw.realname.pepper.value=p",
                        "mbw.realname.aliyun.access-key-id=ak",
                        "mbw.realname.aliyun.access-key-secret=secret",
                        "mbw.realname.aliyun.scene-id=100200")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause().hasMessageContaining("mbw.realname.dek.base64");
                });
    }

    @Test
    void prod_profile_missing_pepper_should_fail_startup() {
        contextRunner
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues(
                        "mbw.realname.dev-bypass=false",
                        "mbw.realname.dek.base64=" + dummyDek32(),
                        "mbw.realname.aliyun.access-key-id=ak",
                        "mbw.realname.aliyun.access-key-secret=secret",
                        "mbw.realname.aliyun.scene-id=100200")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause().hasMessageContaining("mbw.realname.pepper.value");
                });
    }

    @Test
    void prod_profile_missing_aliyun_access_key_should_fail_startup() {
        contextRunner
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues(
                        "mbw.realname.dev-bypass=false",
                        "mbw.realname.dek.base64=" + dummyDek32(),
                        "mbw.realname.pepper.value=p",
                        "mbw.realname.aliyun.access-key-secret=secret",
                        "mbw.realname.aliyun.scene-id=100200")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause().hasMessageContaining("access-key-id");
                });
    }

    private static String dummyDek32() {
        // 32 zero bytes encoded base64 — passes RealnameProviderStartupValidator
        // (which only checks presence, not crypto-validity); EnvDekCipherService
        // is not part of this slim context so its 32-byte assertion does not run.
        return "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    }

    @Configuration
    @Import({
        BypassRealnameClient.class,
        AliyunRealnameClient.class,
        AliyunRealnameClientConfig.class,
        RealnameProviderConfig.class
    })
    static class RealnameTestContext {}
}
