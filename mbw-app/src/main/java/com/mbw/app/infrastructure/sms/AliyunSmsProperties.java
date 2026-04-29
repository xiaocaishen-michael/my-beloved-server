package com.mbw.app.infrastructure.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Aliyun SMS gateway credentials and signing config (T1b).
 *
 * <p>Bound from {@code mbw.sms.aliyun.*}. The class is auto-discovered
 * by {@code @ConfigurationPropertiesScan} on {@code MbwApplication}, so
 * binding happens unconditionally — even when {@code mbw.sms.provider}
 * is unset (dev/test). For that reason fields are <b>not</b>
 * {@code @NotBlank}-validated here; if the values are missing,
 * {@link AliyunSmsConfig} (which is gated by
 * {@code @ConditionalOnProperty}) is the one that fails-fast at SDK
 * client construction. Adding {@code @Validated} here would make the
 * test profile boot fail simply because Aliyun creds are absent —
 * which they should be in any non-prod environment.
 *
 * <p>{@link #endpoint()} defaults to the China-mainland region; override
 * via {@code mbw.sms.aliyun.endpoint} for international SMS or staging.
 */
@ConfigurationProperties(prefix = "mbw.sms.aliyun")
public record AliyunSmsProperties(String accessKeyId, String accessKeySecret, String signName, String endpoint) {

    public AliyunSmsProperties {
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "dysmsapi.aliyuncs.com";
        }
    }
}
