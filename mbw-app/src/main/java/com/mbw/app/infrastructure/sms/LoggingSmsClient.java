package com.mbw.app.infrastructure.sms;

import com.mbw.shared.api.sms.SmsClient;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 兜底 {@link SmsClient}，发送时仅打日志、不调用任何外部网关。
 *
 * <p>激活条件（{@code @ConditionalOnProperty}）：
 *
 * <ul>
 *   <li>{@code mbw.sms.provider} 显式设为 {@code log}，或
 *   <li>{@code mbw.sms.provider} 未设（{@code matchIfMissing=true}）
 * </ul>
 *
 * <p>这样三个 SmsClient 实现 (mock / aliyun / log) 都通过 provider
 * 值互斥选型，避免 {@code @ConditionalOnMissingBean} 在 @Component
 * 上的 race condition（多个 bean 同时评估 missing 条件时结果不稳定）。
 *
 * <p>典型场景：
 *
 * <ul>
 *   <li>本地 dev 不想配 SMTP / Aliyun，仅起 Spring 验证业务路径
 *   <li>CI 单元测试场景（无真实 mail / SMS 通道）
 *   <li>容器内 misconfiguration 时 fail-soft：服务起得来，调 SMS 时仅
 *     log，不抛异常 — 让排查更容易
 * </ul>
 *
 * <p>Logs 仅 phone + templateId + params keys，**绝不**记 code value
 * 本身（CLAUDE.md § 四 log-safety）。
 */
@Component
@ConditionalOnProperty(prefix = "mbw.sms", name = "provider", havingValue = "log", matchIfMissing = true)
public class LoggingSmsClient implements SmsClient {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingSmsClient.class);

    @Override
    public void send(String phone, String templateId, Map<String, String> params) {
        LOG.info("[stub-sms] would-send template={} to phone={} (params keys: {})", templateId, phone, params.keySet());
    }
}
