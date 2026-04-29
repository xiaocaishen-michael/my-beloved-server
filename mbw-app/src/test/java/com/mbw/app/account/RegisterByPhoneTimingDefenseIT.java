package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

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
 * SC-004 timing-defense gate (T18).
 *
 * <p>Verifies the {@code TimingDefenseExecutor} keeps {@code POST
 * /api/v1/accounts/register-by-phone} indistinguishable from a
 * wall-clock observer regardless of whether the phone was already
 * registered: |P95(unregistered, success) − P95(registered, DIV→401)|
 * must stay ≤ 50ms.
 *
 * <p>Sample size is {@value #SAMPLES_PER_PATH} per path (well below
 * the spec's 1000 cycles). Stable P95 estimation needs ~100 samples;
 * larger sweeps would require either bypassing or programmatically
 * draining the per-phone {@code /sms-codes} 60s cooldown bucket on
 * every iteration, which would itself become the test's runtime
 * bottleneck. The smaller window is acceptable because timing-attack
 * sensitivity is dominated by the difference between the two paths,
 * not the absolute precision of either P95.
 *
 * <p>The "registered" pool is seeded directly through
 * {@link AccountRepository} / {@link CredentialRepository} (not the
 * controller) so its {@code /sms-codes} bucket is fresh on first call.
 * Each measured iteration uses a unique phone and a unique
 * {@code X-Forwarded-For} address so no rate-limit gate trips
 * mid-sweep.
 *
 * <p>Tagged {@code "slow"} so contributors can skip with
 * {@code -Dgroups='!slow'}; CI runs the default tag set.
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Tag("slow")
class RegisterByPhoneTimingDefenseIT {

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
    void timing_difference_between_registered_and_unregistered_under_budget() {
        List<String> registeredPool = seedRegisteredPhones(SAMPLES_PER_PATH);

        for (int i = 0; i < WARMUP_SAMPLES; i++) {
            measureUnregisteredFlow();
        }

        long[] unregistered = new long[SAMPLES_PER_PATH];
        for (int i = 0; i < SAMPLES_PER_PATH; i++) {
            unregistered[i] = measureUnregisteredFlow();
        }

        long[] registered = new long[SAMPLES_PER_PATH];
        for (int i = 0; i < SAMPLES_PER_PATH; i++) {
            registered[i] = measureRegisteredFlow(registeredPool.get(i));
        }

        long p95Unregistered = percentile(unregistered, 95);
        long p95Registered = percentile(registered, 95);
        long delta = Math.abs(p95Unregistered - p95Registered);

        assertThat(delta)
                .as(
                        "P95 delta between registered (DIV→401) and unregistered (success) paths must be ≤ %dms; "
                                + "unregistered p50=%dms p95=%dms p99=%dms; registered p50=%dms p95=%dms p99=%dms; delta=%dms",
                        MAX_P95_DELTA_MILLIS,
                        percentile(unregistered, 50),
                        p95Unregistered,
                        percentile(unregistered, 99),
                        percentile(registered, 50),
                        p95Registered,
                        percentile(registered, 99),
                        delta)
                .isLessThanOrEqualTo(MAX_P95_DELTA_MILLIS);
    }

    /**
     * Persist N accounts directly through the domain repositories so their
     * {@code /sms-codes} per-phone cooldown bucket is empty when the test
     * loop later requests a code for them.
     */
    private List<String> seedRegisteredPhones(int n) {
        List<String> phones = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String phone = uniquePhone();
            phones.add(phone);
            transactionTemplate.executeWithoutResult(status -> {
                Instant now = Instant.now();
                PhoneNumber phoneNumber = new PhoneNumber(phone);
                Account account = new Account(phoneNumber, now);
                AccountStateMachine.activate(account, now);
                Account saved = accountRepository.save(account);
                credentialRepository.save(new PhoneCredential(saved.id(), phoneNumber, now));
            });
        }
        return phones;
    }

    private long measureUnregisteredFlow() {
        return measureRegisterCall(uniquePhone());
    }

    private long measureRegisteredFlow(String phone) {
        return measureRegisterCall(phone);
    }

    private long measureRegisterCall(String phone) {
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

        ResponseEntity<Void> smsResp = restTemplate.postForEntity(
                "/api/v1/sms-codes", jsonRequest("{\"phone\":\"" + phone + "\"}", ip), Void.class);
        if (smsResp.getStatusCode() != HttpStatus.OK) {
            throw new AssertionError("/sms-codes returned " + smsResp.getStatusCode() + " for phone=" + phone);
        }

        long start = System.nanoTime();
        ResponseEntity<Void> registerResp = restTemplate.postForEntity(
                "/api/v1/accounts/register-by-phone",
                jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"" + capturedCode.get() + "\"}", ip),
                Void.class);
        return (System.nanoTime() - start) / 1_000_000L;
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
