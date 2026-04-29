package com.mbw.app.infrastructure.sms;

import com.aliyun.dysmsapi20170525.Client;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Aliyun SMS SDK {@link Client} bean when
 * {@code mbw.sms.provider=aliyun} (T1b). Split out from
 * {@link AliyunSmsClient} so unit tests can construct the client with
 * a mocked SDK without bootstrapping the production
 * credentials path.
 */
@Configuration
@ConditionalOnProperty(prefix = "mbw.sms", name = "provider", havingValue = "aliyun")
@EnableConfigurationProperties(AliyunSmsProperties.class)
public class AliyunSmsConfig {

    /**
     * Aliyun SDK client. We re-validate required credentials here (rather
     * than via {@code @NotBlank} on the properties record) because the
     * properties bean is auto-scanned and binds unconditionally; failing
     * fast in this conditional bean keeps dev/test boots clean while
     * still rejecting a misconfigured production startup.
     */
    @Bean
    @ConditionalOnMissingBean(Client.class)
    public Client aliyunDysmsClient(AliyunSmsProperties properties) throws Exception {
        requireNonBlank("mbw.sms.aliyun.accessKeyId", properties.accessKeyId());
        requireNonBlank("mbw.sms.aliyun.accessKeySecret", properties.accessKeySecret());
        requireNonBlank("mbw.sms.aliyun.signName", properties.signName());
        return new Client(AliyunSmsClient.buildSdkConfig(properties));
    }

    private static void requireNonBlank(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    propertyName + " must be set when mbw.sms.provider=aliyun (cannot start with blank credential)");
        }
    }
}
