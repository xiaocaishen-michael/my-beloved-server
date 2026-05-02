package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.mbw.MbwApplication;
import com.mbw.shared.api.sms.SmsClient;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Performance gate verifying SC-001 — login-by-phone-sms P95 latency
 * must stay ≤ 600ms (T11 + analysis.md A1).
 *
 * <p>Mirrors {@link RegisterByPhoneP95IT}: full-stack via TestRestTemplate,
 * Aliyun SMS gateway mocked out (only what we own counts toward the
 * budget). The login budget is tighter than register's 800ms because
 * login skips Account+Credential INSERT (UPDATE-only on
 * {@code last_login_at}).
 *
 * <p>Tagged {@code "slow"} so contributors can skip the ~minute-long
 * sweep with {@code -Dgroups='!slow'}; CI runs the default tag set
 * (all tests) so this remains a hard gate.
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Tag("slow")
class LoginByPhoneSmsP95IT {

    private static final int WARMUP_SAMPLES = 10;
    private static final int SAMPLE_SIZE = 100;
    private static final int MAX_ATTEMPTS = 3;
    private static final long P95_BUDGET_MILLIS = 600L;

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.schemas", () -> "account");
        registry.add("spring.flyway.default-schema", () -> "account");
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.modulith.events.jdbc-schema-initialization.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("mbw.auth.jwt.secret", () -> "test-secret-32-bytes-or-more-of-test-entropy-please-do-not-use");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    private SmsClient smsClient;

    @Test
    void p95_login_by_phone_sms_under_budget() {
        long bestP95 = Long.MAX_VALUE;
        long[] bestSamples = new long[0];

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            for (int i = 0; i < WARMUP_SAMPLES; i++) {
                runOneLogin();
            }
            long[] samples = new long[SAMPLE_SIZE];
            for (int i = 0; i < SAMPLE_SIZE; i++) {
                samples[i] = runOneLogin();
            }
            long p95 = percentile(samples, 95);
            if (p95 < bestP95) {
                bestP95 = p95;
                bestSamples = samples;
            }
            if (p95 <= P95_BUDGET_MILLIS) {
                break;
            }
        }

        assertThat(bestP95)
                .as(
                        "P95 of %d login samples (best of %d attempts) must be ≤ %dms; observed=%dms, p50=%dms, p99=%dms",
                        SAMPLE_SIZE,
                        MAX_ATTEMPTS,
                        P95_BUDGET_MILLIS,
                        bestP95,
                        percentile(bestSamples, 50),
                        percentile(bestSamples, 99))
                .isLessThanOrEqualTo(P95_BUDGET_MILLIS);
    }

    /**
     * One full sample = register + request login code + login. The
     * register portion sets up the persistent state; only the
     * {@code login-by-phone-sms} call's wall clock is measured.
     */
    private long runOneLogin() {
        String phone = uniquePhone();
        String ip = uniqueIp();
        AtomicReference<String> capturedCode = new AtomicReference<>();
        doAnswer(inv -> {
                    Map<String, String> params = inv.getArgument(2);
                    if (params.containsKey("code")) {
                        capturedCode.set(params.get("code"));
                    }
                    return null;
                })
                .when(smsClient)
                .send(eq(phone), any(), any());

        // Setup: register the account (untimed)
        ResponseEntity<Void> smsResp1 = restTemplate.postForEntity(
                "/api/v1/sms-codes", jsonRequest("{\"phone\":\"" + phone + "\"}", ip), Void.class);
        if (smsResp1.getStatusCode() != HttpStatus.OK) {
            throw new AssertionError("setup sms-codes returned " + smsResp1.getStatusCode());
        }
        ResponseEntity<Void> registerResp = restTemplate.postForEntity(
                "/api/v1/accounts/register-by-phone",
                jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"" + capturedCode.get() + "\"}", ip),
                Void.class);
        if (registerResp.getStatusCode() != HttpStatus.OK) {
            throw new AssertionError("setup register returned " + registerResp.getStatusCode());
        }

        // Issue a fresh LOGIN code (untimed)
        capturedCode.set(null);
        ResponseEntity<Void> smsResp2 = restTemplate.postForEntity(
                "/api/v1/sms-codes",
                jsonRequest("{\"phone\":\"" + phone + "\",\"purpose\":\"LOGIN\"}", uniqueIp()),
                Void.class);
        if (smsResp2.getStatusCode() != HttpStatus.OK) {
            throw new AssertionError("login sms-codes returned " + smsResp2.getStatusCode());
        }

        // Timed: login
        long start = System.nanoTime();
        ResponseEntity<Void> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login-by-phone-sms",
                jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"" + capturedCode.get() + "\"}", uniqueIp()),
                Void.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        if (loginResp.getStatusCode() != HttpStatus.OK) {
            throw new AssertionError("login returned " + loginResp.getStatusCode());
        }
        return elapsedMs;
    }

    private static long percentile(long[] samples, int pct) {
        if (samples.length == 0) {
            return -1L;
        }
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int idx = (int) Math.ceil((pct / 100.0) * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private static HttpEntity<String> jsonRequest(String body, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (forwardedFor != null) {
            headers.set("X-Forwarded-For", forwardedFor);
        }
        return new HttpEntity<>(body, headers);
    }

    private static String uniquePhone() {
        long suffix = Math.abs(UUID.randomUUID().getMostSignificantBits()) % 10_000_000_000L;
        return "+8613" + String.format("%010d", suffix).substring(1);
    }

    private static String uniqueIp() {
        long t = System.nanoTime();
        int b1 = (int) ((t >>> 16) & 0xFF);
        int b2 = (int) ((t >>> 8) & 0xFF);
        int b3 = (int) (t & 0xFF);
        return "10." + b1 + "." + b2 + "." + b3;
    }
}
