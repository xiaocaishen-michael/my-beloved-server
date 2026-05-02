package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.MbwApplication;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.PasswordCredential;
import com.mbw.account.domain.model.PasswordHash;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.account.domain.service.PasswordHasher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * SC-003 timing-defense gate for login-by-password (P1.2 T6).
 *
 * <p>Verifies the {@link com.mbw.account.domain.service.TimingDefenseExecutor#executeWithBCryptVerify}
 * keeps {@code POST /api/v1/auth/login-by-password} indistinguishable
 * from a wall-clock observer across three failure paths:
 *
 * <ol>
 *   <li>account exists + has password + wrong password (real BCrypt
 *       verify against real hash → false)
 *   <li>account exists + no password set (BCrypt verify against
 *       DUMMY_HASH → false)
 *   <li>account does not exist (BCrypt verify against DUMMY_HASH →
 *       false)
 * </ol>
 *
 * <p>All three should converge in P95 because the entry-level BCrypt
 * verify (cost 8) dominates the latency. Asserts pairwise |Δ P95| ≤
 * 50ms.
 *
 * <p>Tagged {@code "slow"} so contributors can skip with
 * {@code -Dgroups='!slow'}; CI runs the default tag set.
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Tag("slow")
class LoginByPasswordTimingDefenseIT {

    private static final int WARMUP_SAMPLES = 20;
    private static final int SAMPLES_PER_PATH = 100;
    private static final long MAX_P95_DELTA_MILLIS = 50L;
    private static final String VALID_PASSWORD = "MyStrongP4ss";
    private static final String WRONG_PASSWORD = "TotallyDifferentP4ss";

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
    private PasswordHasher passwordHasher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void timing_difference_across_3_failure_paths_under_budget() {
        List<String> withPasswordPool = seedWithPasswordPool(SAMPLES_PER_PATH);
        List<String> noPasswordPool = seedNoPasswordPool(SAMPLES_PER_PATH);

        // Warmup against unregistered (cheap to set up)
        for (int i = 0; i < WARMUP_SAMPLES; i++) {
            measureUnregistered();
        }

        long[] withPassword = new long[SAMPLES_PER_PATH];
        for (int i = 0; i < SAMPLES_PER_PATH; i++) {
            withPassword[i] = measureWrongPassword(withPasswordPool.get(i));
        }

        long[] noPassword = new long[SAMPLES_PER_PATH];
        for (int i = 0; i < SAMPLES_PER_PATH; i++) {
            noPassword[i] = measureNoPassword(noPasswordPool.get(i));
        }

        long[] unregistered = new long[SAMPLES_PER_PATH];
        for (int i = 0; i < SAMPLES_PER_PATH; i++) {
            unregistered[i] = measureUnregistered();
        }

        long p95WithPassword = percentile(withPassword, 95);
        long p95NoPassword = percentile(noPassword, 95);
        long p95Unregistered = percentile(unregistered, 95);

        long deltaWrongVsNoPwd = Math.abs(p95WithPassword - p95NoPassword);
        long deltaWrongVsUnregistered = Math.abs(p95WithPassword - p95Unregistered);
        long deltaNoPwdVsUnregistered = Math.abs(p95NoPassword - p95Unregistered);

        assertThat(deltaWrongVsNoPwd)
                .as(
                        "wrong-password vs no-password P95 must be ≤ %dms; wrong=%dms noPwd=%dms",
                        MAX_P95_DELTA_MILLIS, p95WithPassword, p95NoPassword)
                .isLessThanOrEqualTo(MAX_P95_DELTA_MILLIS);
        assertThat(deltaWrongVsUnregistered)
                .as(
                        "wrong-password vs unregistered P95 must be ≤ %dms; wrong=%dms unreg=%dms",
                        MAX_P95_DELTA_MILLIS, p95WithPassword, p95Unregistered)
                .isLessThanOrEqualTo(MAX_P95_DELTA_MILLIS);
        assertThat(deltaNoPwdVsUnregistered)
                .as(
                        "no-password vs unregistered P95 must be ≤ %dms; noPwd=%dms unreg=%dms",
                        MAX_P95_DELTA_MILLIS, p95NoPassword, p95Unregistered)
                .isLessThanOrEqualTo(MAX_P95_DELTA_MILLIS);
    }

    private List<String> seedWithPasswordPool(int n) {
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
                PasswordHash hash = passwordHasher.hash(VALID_PASSWORD);
                credentialRepository.save(new PasswordCredential(saved.id(), hash, now));
            });
        }
        return phones;
    }

    private List<String> seedNoPasswordPool(int n) {
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

    private long measureWrongPassword(String phone) {
        return measureLoginCall(phone, WRONG_PASSWORD);
    }

    private long measureNoPassword(String phone) {
        // Submit any non-blank password — account has no PasswordCredential, so
        // hashSupplier returns DUMMY_HASH → BCrypt verify fails → 401
        return measureLoginCall(phone, VALID_PASSWORD);
    }

    private long measureUnregistered() {
        return measureLoginCall(uniquePhone(), VALID_PASSWORD);
    }

    private long measureLoginCall(String phone, String password) {
        long start = System.nanoTime();
        ResponseEntity<Void> resp = restTemplate.postForEntity(
                "/api/v1/auth/login-by-password",
                jsonRequest("{\"phone\":\"" + phone + "\",\"password\":\"" + password + "\"}", uniqueIp()),
                Void.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        if (resp.getStatusCode() != HttpStatus.UNAUTHORIZED && resp.getStatusCode() != HttpStatus.OK) {
            throw new AssertionError("login returned unexpected " + resp.getStatusCode());
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
