package com.mbw.app.infrastructure.email;

import com.mbw.shared.api.email.EmailMessage;
import com.mbw.shared.api.email.EmailSendException;
import com.mbw.shared.api.email.EmailSender;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Production {@link EmailSender} backed by Resend's HTTPS API
 * (ADR-0013 second amendment).
 *
 * <p>Active when {@code mbw.email.provider=resend}; otherwise the
 * {@link LoggingEmailSender} fallback steps in via the
 * {@code matchIfMissing=true} branch.
 *
 * <p>Resilience4j programmatic Retry (3 attempts, 200ms-400ms
 * exponential backoff) wraps each call to absorb transient gateway
 * issues. "Transient" means: network IO failure (mapped to
 * {@link ResourceAccessException} by Spring), or HTTP 5xx (mapped to
 * an internal {@link TransientEmailException} marker). Permanent
 * failures (HTTP 4xx — malformed payload, invalid api key, blocked
 * domain) skip retry and surface immediately as
 * {@link EmailSendException} so the caller can fail fast without
 * burning rate-limit quota.
 *
 * <p>Mirrors {@link com.mbw.app.infrastructure.sms.AliyunSmsClient}'s
 * retry pattern — the two external-messaging clients should look
 * identical to readers (same retry constants, same transient/permanent
 * split, same test-only ctor for backoff override).
 */
@Component
@ConditionalOnProperty(prefix = "mbw.email", name = "provider", havingValue = "resend")
@EnableConfigurationProperties(ResendProperties.class)
public class ResendEmailClient implements EmailSender {

    private static final Logger LOG = LoggerFactory.getLogger(ResendEmailClient.class);
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(200);
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;

    private final RestClient restClient;
    private final ResendProperties props;
    private final Retry retry;

    // Spring sees two declared constructors and can't auto-pick — the
    // single-public-constructor inference (4.3+) only fires when there's
    // exactly one. The other ctor (package-private, no @Autowired) stays
    // for tests so we can drive backoff with 1ms intervals.
    @Autowired
    public ResendEmailClient(ResendProperties props) {
        this(props, DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_BACKOFF, DEFAULT_BACKOFF_MULTIPLIER);
    }

    ResendEmailClient(ResendProperties props, int maxAttempts, Duration initialBackoff, double backoffMultiplier) {
        // Validate at consumer-side rather than @NotBlank on Properties,
        // so dev/test ConfigurationPropertiesScan does not fail boot
        // when these are unset (resend provider not selected).
        requireNonBlank("mbw.email.resend.api-key", props.apiKey());
        this.props = props;
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(buildRequestFactory(props))
                .build();
        this.retry = buildRetry(maxAttempts, initialBackoff, backoffMultiplier);
    }

    private static void requireNonBlank(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when mbw.email.provider=resend");
        }
    }

    @Override
    public void send(EmailMessage message) {
        try {
            Retry.decorateCheckedRunnable(retry, () -> doSend(message)).run();
        } catch (EmailSendException ex) {
            throw ex;
        } catch (Throwable ex) {
            // Retry surfaces the *last* attempt's exception; any
            // unchecked / runtime type that was not pre-mapped lands here.
            throw new EmailSendException("Email send failed via Resend gateway", ex);
        }
    }

    private void doSend(EmailMessage msg) {
        ResendEmailRequest body = new ResendEmailRequest(msg.from(), List.of(msg.to()), msg.subject(), msg.text());
        try {
            restClient
                    .post()
                    .uri("/emails")
                    .header("Authorization", "Bearer " + props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError(), (req, resp) -> {
                        // 4xx = permanent. Throw EmailSendException (not retried).
                        throw new EmailSendException("Resend rejected request (HTTP "
                                + resp.getStatusCode().value() + "): " + readBodySafely(resp));
                    })
                    .onStatus(status -> status.is5xxServerError(), (req, resp) -> {
                        // 5xx = transient. Throw a marker the retry policy retries on.
                        throw new TransientEmailException(
                                "Resend 5xx (HTTP " + resp.getStatusCode().value() + ")");
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException ex) {
            // Network error (connect refused / read timeout / DNS) →
            // transient; let retry handle. Wrap so isTransient picks it up.
            throw new TransientEmailException("Resend network error: " + ex.getMessage(), ex);
        }
    }

    private static String readBodySafely(org.springframework.http.client.ClientHttpResponse resp) {
        try {
            return new String(resp.getBody().readAllBytes());
        } catch (IOException ex) {
            return "<unable to read response body: " + ex.getMessage() + ">";
        }
    }

    private static SimpleClientHttpRequestFactory buildRequestFactory(ResendProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.connectTimeout().toMillis());
        factory.setReadTimeout((int) props.readTimeout().toMillis());
        return factory;
    }

    private static Retry buildRetry(int maxAttempts, Duration initialBackoff, double backoffMultiplier) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(initialBackoff, backoffMultiplier))
                .retryOnException(TRANSIENT_EXCEPTION_PREDICATE)
                .build();
        Retry instance = Retry.of("resend-email", config);
        instance.getEventPublisher()
                .onRetry(event -> LOG.warn(
                        "[resend-email] retry attempt={} after exception={}",
                        event.getNumberOfRetryAttempts(),
                        event.getLastThrowable() == null
                                ? "n/a"
                                : event.getLastThrowable().getClass().getSimpleName()));
        return instance;
    }

    private static final Predicate<Throwable> TRANSIENT_EXCEPTION_PREDICATE = ResendEmailClient::isTransient;

    private static boolean isTransient(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof TransientEmailException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * Internal marker — Resend HTTP 5xx and network errors raise this
     * so the Resilience4j Retry policy treats the attempt as a failure
     * eligible for retry.
     */
    static final class TransientEmailException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TransientEmailException(String message) {
            super(message);
        }

        TransientEmailException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** On-the-wire representation of a Resend "Send Email" request body. */
    private record ResendEmailRequest(String from, List<String> to, String subject, String text) {}
}
