package com.mbw.app.infrastructure.sms;

import com.mbw.shared.api.sms.SmsClient;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Stand-in {@link SmsClient} that logs send attempts instead of
 * calling the upstream gateway.
 *
 * <p>Active in the {@code dev} and {@code test} profiles + as the
 * {@code @ConditionalOnMissingBean} fallback when no real provider is
 * wired (e.g. running locally without the Aliyun starter). T1b will
 * add the production {@code AliyunSmsClient} which takes precedence
 * via {@code @ConditionalOnProperty(mbw.sms.provider=aliyun)}.
 *
 * <p>Logs only the phone + template id; <b>never the {@code code}
 * value</b> per CLAUDE.md § 四 log-safety.
 */
@Component
@Profile({"dev", "test", "default"})
@ConditionalOnMissingBean(name = "aliyunSmsClient")
public class LoggingSmsClient implements SmsClient {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingSmsClient.class);

    @Override
    public void send(String phone, String templateId, Map<String, String> params) {
        LOG.info("[stub-sms] would-send template={} to phone={} (params keys: {})", templateId, phone, params.keySet());
    }
}
