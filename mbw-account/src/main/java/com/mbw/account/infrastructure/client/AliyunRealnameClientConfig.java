package com.mbw.account.infrastructure.client;

import com.aliyun.cloudauth20190307.Client;
import com.aliyun.teaopenapi.models.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Aliyun cloud-auth SDK {@link Client} bean when the bypass
 * client is not active (realname-verification spec T10). Split out from
 * {@link AliyunRealnameClient} so unit tests construct the client with
 * a mocked SDK without bootstrapping the production credentials path.
 *
 * <p>The {@code @ConditionalOnProperty(matchIfMissing=true)} makes Aliyun
 * the default in any profile that does not explicitly opt into bypass
 * mode — production cannot accidentally skip real upstream verification
 * by leaving the flag unset.
 *
 * <p>Connect / read timeouts are set on the SDK Config rather than on
 * the per-call {@code RuntimeOptions} so they apply uniformly to all
 * cloud-auth API calls (initFaceVerify + describeVerifyResult).
 */
@Configuration
@ConditionalOnProperty(name = "mbw.realname.dev-bypass", havingValue = "false", matchIfMissing = true)
@EnableConfigurationProperties(AliyunRealnameProperties.class)
public class AliyunRealnameClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 8_000;

    @Bean
    @ConditionalOnMissingBean(name = "aliyunCloudauthClient")
    public Client aliyunCloudauthClient(AliyunRealnameProperties properties) throws Exception {
        requireNonBlank("mbw.realname.aliyun.access-key-id", properties.accessKeyId());
        requireNonBlank("mbw.realname.aliyun.access-key-secret", properties.accessKeySecret());
        requireNonBlank("mbw.realname.aliyun.scene-id", properties.sceneId());
        Config config = new Config()
                .setAccessKeyId(properties.accessKeyId())
                .setAccessKeySecret(properties.accessKeySecret())
                .setEndpoint(properties.endpoint())
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS);
        return new Client(config);
    }

    private static void requireNonBlank(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must be set when mbw.realname.dev-bypass is not true");
        }
    }
}
