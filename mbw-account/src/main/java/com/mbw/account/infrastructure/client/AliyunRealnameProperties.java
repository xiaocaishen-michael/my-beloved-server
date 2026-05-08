package com.mbw.account.infrastructure.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Aliyun cloud-auth (RPBasic) credentials and scene config for
 * {@link AliyunRealnameClient} (realname-verification spec T10).
 *
 * <p>Bound from {@code mbw.realname.aliyun.*}. Picked up by
 * {@code @ConfigurationPropertiesScan} on {@code MbwApplication}, so
 * binding happens unconditionally — even when the bypass client is
 * selected (dev/test). For that reason fields are <b>not</b>
 * {@code @NotBlank}-validated here; if the values are missing,
 * {@link AliyunRealnameClientConfig} (gated by
 * {@code @ConditionalOnProperty(mbw.realname.dev-bypass=false)}) fails
 * fast at SDK client construction. Adding {@code @Validated} here would
 * break dev/test boots that have no Aliyun credentials by design.
 *
 * <p>{@link #endpoint()} defaults to the China-mainland cloud-auth
 * endpoint; override via {@code mbw.realname.aliyun.endpoint} for
 * international or sandbox endpoints.
 *
 * @param accessKeyId Aliyun RAM access key id
 * @param accessKeySecret Aliyun RAM access key secret
 * @param endpoint OpenAPI endpoint host (no scheme)
 * @param sceneId Aliyun cloud-auth console-allocated scene id required
 *     by RPBasic InitFaceVerify
 */
@ConfigurationProperties(prefix = "mbw.realname.aliyun")
public record AliyunRealnameProperties(String accessKeyId, String accessKeySecret, String endpoint, String sceneId) {

    public AliyunRealnameProperties {
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "cloudauth.aliyuncs.com";
        }
    }
}
