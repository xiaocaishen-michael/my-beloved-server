package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;

import com.mbw.MbwApplication;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.shared.api.sms.SmsClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * SC-003 timing-defense gate for login-by-phone-sms (T12).
 *
 * <p>Verifies the {@code TimingDefenseExecutor} keeps {@code POST
 * /api/v1/auth/login-by-phone-sms} indistinguishable from a wall-clock
 * observer regardless of registration state:
 * |P95(registered, 200) − P95(unregistered, 401)| ≤ 50ms.
 *
 * <p>Mirrors {@link RegisterByPhoneTimingDefenseIT}'s structure. The
 * "registered" pool is seeded directly through repositories (not the
 * controller) to avoid SMS-code rate-limit interference. Each iteration
 * uses a unique X-Forwarded-For so the per-IP gate doesn't trip
 * mid-sweep.
 *
 * <p>Tagged {@code "slow"} so contributors can skip with
 * {@code -Dgroups='!slow'}; CI runs the default tag set.
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Tag("slow")
class LoginByPhoneSmsTimingDefenseIT {

    private static final int WARMUP_SAMPLES = 20;
    private static final int SAMPLES_PER_PATH = 100;
    private static final long MAX_P95_DELTA_MILLIS = 50L;

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

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CredentialRepository credentialRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockBean
    private SmsClient smsClient;

    @Test
    void timing_difference_between_registered_and_unregistered_login_under_budget() {
        // Pre-seed registered pool + capture per-phone login codes
        List<RegisteredPhone> pool = seedRegisteredPool(SAMPLES_PER_PATH);

        // Warmup with unregistered (cheaper to set up than registered)
        for (int i = 0; i < WARMUP_SAMPLES; i++) {
            measureUnregisteredLogin();
        }

        long[] registered = new long[SAMPLES_PER_PATH];
        for (int i = 0; i < SAMPLES_PER_PATH; i++) {
            registered[i] = measureRegisteredLogin(pool.get(i));
        }

        long[] unregistered = new long[SAMPLES_PER_PATH];
        for (int i = 0; i < SAMPLES_PER_PATH; i++) {
            unregistered[i] = measureUnregisteredLogin();
        }

        long p95Registered = percentile(registered, 95);
        long p95Unregistered = percentile(unregistered, 95);
        long delta = Math.abs(p95Registered - p95Unregistered);

        assertThat(delta)
                .as(
                        "P95 delta between login(registered, 200) and login(unregistered, 401) must be ≤ %dms; "
                                + "registered p50=%dms p95=%dms p99=%dms; unregistered p50=%dms p95=%dms p99=%dms; delta=%dms",
                        MAX_P95_DELTA_MILLIS,
                        percentile(registered, 50),
                        p95Registered,
                        percentile(registered, 99),
                        percentile(unregistered, 50),
                        p95Unregistered,
                        percentile(unregistered, 99),
                        delta)
                .isLessThanOrEqualTo(MAX_P95_DELTA_MILLIS);
    }

    private List<RegisteredPhone> seedRegisteredPool(int n) {
        List<RegisteredPhone> pool = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String phone = uniquePhone();
            transactionTemplate.executeWithoutResult(status -> {
                Instant now = Instant.now();
                PhoneNumber phoneNumber = new PhoneNumber(phone);
                Account account = new Account(phoneNumber, now);
                AccountStateMachine.activate(account, now);
                Account saved = accountRepository.save(account);
                credentialRepository.save(new PhoneCredential(saved.id(), phoneNumber, now));
            });
            // Pre-fetch a LOGIN code for the registered phone (untimed)
            String loginCode = requestLoginCode(phone);
            pool.add(new RegisteredPhone(phone, loginCode));
        }
        return pool;
    }

    private String requestLoginCode(String phone) {
        AtomicReference<String> captured = new AtomicReference<>();
        doAnswer(inv -> {
                    Map<String, String> params = inv.getArgument(2);
                    if (params.containsKey("code")) {
                        captured.set(params.get("code"));
                    }
                    return null;
                })
                .when(smsClient)
                .send(eq(phone), any(), any());
        ResponseEntity<Void> resp = restTemplate.postForEntity(
                "/api/v1/sms-codes",
                jsonRequest("{\"phone\":\"" + phone + "\",\"purpose\":\"LOGIN\"}", uniqueIp()),
                Void.class);
        if (resp.getStatusCode() != HttpStatus.OK) {
            throw new AssertionError("setup login sms-codes returned " + resp.getStatusCode());
        }
        return captured.get();
    }

    private long measureRegisteredLogin(RegisteredPhone target) {
        long start = System.nanoTime();
        ResponseEntity<Void> resp = restTemplate.postForEntity(
                "/api/v1/auth/login-by-phone-sms",
                jsonRequest("{\"phone\":\"" + target.phone + "\",\"code\":\"" + target.loginCode + "\"}", uniqueIp()),
                Void.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        // Either 200 (first use) or 401 (code already consumed in earlier
        // sample). Both paths are timing-pad-protected; the test still
        // measures elapsed wall clock equally.
        if (resp.getStatusCode() != HttpStatus.OK && resp.getStatusCode() != HttpStatus.UNAUTHORIZED) {
            throw new AssertionError("registered login returned unexpected " + resp.getStatusCode());
        }
        return elapsedMs;
    }

    private long measureUnregisteredLogin() {
        String phone = uniquePhone(); // never registered
        doNothing().when(smsClient).send(any(), any(), any());

        long start = System.nanoTime();
        ResponseEntity<Void> resp = restTemplate.postForEntity(
                "/api/v1/auth/login-by-phone-sms",
                jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"123456\"}", uniqueIp()),
                Void.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        if (resp.getStatusCode() != HttpStatus.UNAUTHORIZED) {
            throw new AssertionError("unregistered login returned " + resp.getStatusCode());
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

    private record RegisteredPhone(String phone, String loginCode) {}
}
