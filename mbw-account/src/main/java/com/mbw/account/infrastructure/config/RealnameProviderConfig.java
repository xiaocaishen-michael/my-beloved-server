package com.mbw.account.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Production fail-fast guard for the realname-verification provider stack
 * (realname-verification spec T11).
 *
 * <p>Routing of the upstream client itself happens via
 * {@code @ConditionalOnProperty} on {@code BypassRealnameClient} (active
 * when {@code mbw.realname.dev-bypass=true}) and {@code AliyunRealnameClient}
 * (active when the same flag is unset or false). This config adds the
 * cross-cutting safety net: in the {@code prod} profile, dev-bypass is
 * forbidden and the cipher / Aliyun credentials must all be present —
 * otherwise the application context fails to start.
 *
 * <p><b>Spec amend</b>: the original spec used an
 * {@code @Bean ApplicationRunner} for the validator, but ApplicationRunner
 * fires after the context is fully built, which is too late for fail-fast
 * semantics under tools like Spring Cloud / k8s liveness probes that may
 * already report the pod as healthy. A bean whose constructor throws
 * (per {@link RealnameProviderStartupValidator}) fails the
 * {@code refresh()} call directly, matching how
 * {@code JwtProperties} compact constructor validation works elsewhere
 * in the codebase.
 */
@Configuration
public class RealnameProviderConfig {

    @Bean
    RealnameProviderStartupValidator realnameProviderStartupValidator(Environment env) {
        return new RealnameProviderStartupValidator(env);
    }
}
