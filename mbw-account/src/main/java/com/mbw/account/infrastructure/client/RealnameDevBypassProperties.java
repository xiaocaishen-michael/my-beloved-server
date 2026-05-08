package com.mbw.account.infrastructure.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised configuration for {@link BypassRealnameClient}
 * (realname-verification spec T9).
 *
 * <p>Bound to {@code mbw.realname.dev-fixed-result} via the
 * {@code MBW_REALNAME_DEV_FIXED_RESULT} environment variable. Accepted
 * values: {@code verified} (default) or {@code failed} — drives the
 * synthetic outcome the bypass client returns without calling any
 * upstream provider.
 *
 * <p>Sibling property {@code mbw.realname.dev-bypass} (boolean) is
 * consumed by {@code @ConditionalOnProperty} on the client itself, not
 * by this record.
 *
 * <p>No {@code @NotBlank} per project convention (see
 * {@code MockSmsProperties} javadoc) — validation at consumer-side so
 * envs which do not opt into dev-bypass mode boot without this var set.
 */
@ConfigurationProperties(prefix = "mbw.realname")
public record RealnameDevBypassProperties(String devFixedResult) {}
