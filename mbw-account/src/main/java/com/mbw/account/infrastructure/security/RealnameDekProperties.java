package com.mbw.account.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised data-encryption-key (DEK) configuration for realname PII
 * (realname-verification spec T8).
 *
 * <p>Bound to {@code mbw.realname.dek.base64} which Docker Compose / K8s
 * inject via the {@code MBW_REALNAME_DEK_BASE64} environment variable —
 * a base64-encoded 32-byte AES-256 key.
 *
 * <p>No {@code @NotBlank} / {@code @Validated} on this record: project
 * convention (see {@code MockSmsProperties} javadoc) is to validate at
 * the consumer side ({@link EnvDekCipherService} constructor) so that
 * dev/test environments which do not select the {@code env-dek} cipher
 * mode can boot without an unrelated env var being set. The cipher
 * service itself is gated by {@code @ConditionalOnProperty}, so its
 * validator only runs when env-dek mode is actually selected.
 */
@ConfigurationProperties(prefix = "mbw.realname.dek")
public record RealnameDekProperties(String base64) {}
