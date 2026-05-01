package com.mbw.app.infrastructure.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbw.shared.api.sms.SmsClient;
import com.mbw.shared.api.sms.SmsSendException;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production {@link SmsClient} backed by Aliyun SMS (T1b).
 *
 * <p>Active only when {@code mbw.sms.provider=aliyun}; in dev/test the
 * stub {@link LoggingSmsClient} keeps the slot. Resilience4j programmatic
 * Retry (3 attempts, 200ms-400ms exponential backoff, cap 1s) wraps each
 * call to absorb transient gateway issues per FR-009.
 *
 * <p>"Transient" means: any {@link IOException} (network) or
 * {@link TeaException} whose code is {@code Throttling.*},
 * {@code ServiceUnavailable}, or {@code isv.BUSINESS_LIMIT_CONTROL}.
 * Permanent failures (illegal signature, blocked phone, malformed
 * template) skip retry and surface immediately as
 * {@link SmsSendException} so the caller can fail fast — retrying a
 * malformed request only burns gateway quota.
 *
 * <p>The Aliyun SDK {@link Client} is injected so unit tests can drive
 * the retry policy with a {@link org.mockito.Mockito Mockito} mock
 * without touching real credentials or HTTP transport.
 */
@Component
@ConditionalOnProperty(prefix = "mbw.sms", name = "provider", havingValue = "aliyun")
public class AliyunSmsClient implements SmsClient {

    private static final Logger LOG = LoggerFactory.getLogger(AliyunSmsClient.class);
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(200);
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;

    private final Client sdkClient;
    private final AliyunSmsProperties properties;
    private final ObjectMapper objectMapper;
    private final Retry retry;

    // Spring sees two declared constructors and can't auto-pick — the
    // single-public-constructor inference (4.3+) only fires when there's
    // exactly one. The other ctor (package-private, no @Autowired) stays
    // for tests so we can drive backoff with 1ms intervals.
    @Autowired
    public AliyunSmsClient(Client sdkClient, AliyunSmsProperties properties, ObjectMapper objectMapper) {
        this.sdkClient = sdkClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.retry = buildRetry(DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_BACKOFF, DEFAULT_BACKOFF_MULTIPLIER);
    }

    AliyunSmsClient(
            Client sdkClient,
            AliyunSmsProperties properties,
            ObjectMapper objectMapper,
            int maxAttempts,
            Duration initialBackoff,
            double backoffMultiplier) {
        this.sdkClient = sdkClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.retry = buildRetry(maxAttempts, initialBackoff, backoffMultiplier);
    }

    @Override
    public void send(String phone, String templateId, Map<String, String> params) {
        try {
            Retry.decorateCheckedRunnable(retry, () -> doSend(phone, templateId, params))
                    .run();
        } catch (SmsSendException ex) {
            throw ex;
        } catch (Throwable ex) {
            // Retry surfaces the *last* attempt's exception; any
            // unchecked / SDK type that was not pre-mapped lands here.
            throw new SmsSendException("SMS send failed via Aliyun gateway", ex);
        }
    }

    private void doSend(String phone, String templateId, Map<String, String> params) throws Exception {
        SendSmsRequest request = new SendSmsRequest()
                .setPhoneNumbers(phone)
                .setSignName(properties.signName())
                .setTemplateCode(templateId)
                .setTemplateParam(serializeParams(params));

        SendSmsResponse response;
        try {
            response = sdkClient.sendSms(request);
        } catch (TeaException tea) {
            if (isTransient(tea)) {
                throw tea;
            }
            throw new SmsSendException(
                    "Aliyun SMS rejected request (code=" + tea.getCode() + ", msg=" + tea.getMessage() + ")", tea);
        }
        SendSmsResponseBody body = response.getBody();
        if (!"OK".equals(body.getCode())) {
            // The Aliyun gateway returned 200 OK but the body indicates
            // a business failure; transient-ness is decided by the same
            // code list as TeaException.
            if (isTransientCode(body.getCode())) {
                throw new TransientSmsException(body.getCode(), body.getMessage());
            }
            throw new SmsSendException(
                    "Aliyun SMS response not OK (code=" + body.getCode() + ", msg=" + body.getMessage() + ")");
        }
    }

    private String serializeParams(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException ex) {
            // Caller handed us un-serializable params — treat as
            // permanent failure (no retry helps).
            throw new SmsSendException("Failed to serialize SMS template params", ex);
        }
    }

    private static Retry buildRetry(int maxAttempts, Duration initialBackoff, double backoffMultiplier) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(initialBackoff, backoffMultiplier))
                .retryOnException(TRANSIENT_EXCEPTION_PREDICATE)
                .build();
        Retry instance = Retry.of("aliyun-sms", config);
        instance.getEventPublisher()
                .onRetry(event -> LOG.warn(
                        "[aliyun-sms] retry attempt={} after exception={}",
                        event.getNumberOfRetryAttempts(),
                        event.getLastThrowable() == null
                                ? "n/a"
                                : event.getLastThrowable().getClass().getSimpleName()));
        return instance;
    }

    private static final Predicate<Throwable> TRANSIENT_EXCEPTION_PREDICATE = AliyunSmsClient::isTransient;

    private static boolean isTransient(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof IOException) {
                return true;
            }
            if (cur instanceof TransientSmsException) {
                return true;
            }
            if (cur instanceof TeaException tea) {
                return isTransientCode(tea.getCode());
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static boolean isTransientCode(String code) {
        if (code == null) {
            return false;
        }
        return code.startsWith("Throttling.")
                || "ServiceUnavailable".equals(code)
                || "isv.BUSINESS_LIMIT_CONTROL".equals(code);
    }

    /**
     * Internal marker — the Aliyun SDK returns body-level errors as a
     * normal {@link SendSmsResponse}; we re-raise as an exception so
     * the Resilience4j Retry policy treats the call as a failure.
     */
    static final class TransientSmsException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        TransientSmsException(String code, String message) {
            super("[" + code + "] " + message);
            this.code = code;
        }

        String getCode() {
            return code;
        }
    }

    static Config buildSdkConfig(AliyunSmsProperties props) {
        return new Config()
                .setAccessKeyId(props.accessKeyId())
                .setAccessKeySecret(props.accessKeySecret())
                .setEndpoint(props.endpoint());
    }
}
