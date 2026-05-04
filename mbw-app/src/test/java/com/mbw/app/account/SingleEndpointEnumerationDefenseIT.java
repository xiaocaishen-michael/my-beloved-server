package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.MbwApplication;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.shared.api.sms.SmsCodeService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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
 * Anti-enumeration timing-defense IT for unified phone-SMS auth (per
 * ADR-0016 + spec {@code phone-sms-auth/spec.md} T5 / SC-003).
 *
 * <p>Drives 3 branches × N (default 50) requests each through the
 * single endpoint and asserts:
 *
 * <ul>
 *   <li>P95 wall-clock latency diff across branches ≤ 50 ms (SC-003)
 *   <li>Success-path branches (registered ACTIVE / unregistered
 *       auto-create) return structurally-identical responses (status,
 *       Content-Type, JSON field set) — an attacker comparing bodies
 *       cannot tell which branch ran
 *   <li>All requests on the failure branch (wrong SMS code) return
 *       401 + {@code INVALID_CREDENTIALS}
 * </ul>
 *
 * <p><b>Scope note</b>: spec T5 calls for 4 branches × 250 (incl.
 * FROZEN). The schema CHECK constraint pins {@code status=ACTIVE} in
 * M1.2, so the FROZEN branch is deferred to M1.3+ when the
 * {@code delete-account} use case adds the FROZEN transition path —
 * unit tests with mocked repositories cover that branch today.
 * Per-branch N is scaled down from 250 → 50 for CI throughput; bumping
 * the constant rescales the assertion automatically.
 *
 * <p>Each request uses a unique phone — the {@code auth:<phone>}
 * 24h-5 bucket would otherwise reject repeats.
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SingleEndpointEnumerationDefenseIT {

    private static final int REQUESTS_PER_BRANCH = 50;
    private static final int WARMUP_PER_BRANCH = 10;
    private static final long P95_DIFF_BUDGET_NANOS = Duration.ofMillis(50).toNanos();

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
    private SmsCodeService smsCodeService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void all_branches_should_be_indistinguishable_by_timing_or_response_shape() {
        // Warmup — drive each branch a few times so JIT compiles the
        // hot path before measurements start. Without this, the first
        // ~10 requests of one branch carry compilation cost and skew P95.
        runBranch(Branch.REGISTERED, WARMUP_PER_BRANCH, false);
        runBranch(Branch.UNREGISTERED, WARMUP_PER_BRANCH, false);
        runBranch(Branch.WRONG_CODE, WARMUP_PER_BRANCH, false);

        List<Sample> registered = runBranch(Branch.REGISTERED, REQUESTS_PER_BRANCH, true);
        List<Sample> unregistered = runBranch(Branch.UNREGISTERED, REQUESTS_PER_BRANCH, true);
        List<Sample> wrongCode = runBranch(Branch.WRONG_CODE, REQUESTS_PER_BRANCH, true);

        assertSuccessShape(registered);
        assertSuccessShape(unregistered);
        assertFailureShape(wrongCode);

        long p95Registered = p95Nanos(registered);
        long p95Unregistered = p95Nanos(unregistered);
        long p95WrongCode = p95Nanos(wrongCode);

        long maxP95 = Math.max(p95Registered, Math.max(p95Unregistered, p95WrongCode));
        long minP95 = Math.min(p95Registered, Math.min(p95Unregistered, p95WrongCode));
        long diff = maxP95 - minP95;

        assertThat(diff)
                .as(
                        "P95 latency diff across 3 branches (registered=%dms, unregistered=%dms, wrongCode=%dms) must be ≤ 50ms",
                        nanosToMs(p95Registered), nanosToMs(p95Unregistered), nanosToMs(p95WrongCode))
                .isLessThanOrEqualTo(P95_DIFF_BUDGET_NANOS);
    }

    private List<Sample> runBranch(Branch branch, int count, boolean measure) {
        List<Sample> samples = measure ? new ArrayList<>(count) : Collections.emptyList();
        for (int i = 0; i < count; i++) {
            String phone = uniquePhone();
            String submittedCode =
                    switch (branch) {
                        case REGISTERED -> {
                            seedActiveAccount(phone);
                            yield smsCodeService.generateAndStore(phone);
                        }
                        case UNREGISTERED -> smsCodeService.generateAndStore(phone);
                        case WRONG_CODE -> "999999"; // no code seeded → verify fails
                    };

            HttpEntity<String> request =
                    jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"" + submittedCode + "\"}", uniqueIp());

            long startNanos = System.nanoTime();
            ResponseEntity<String> resp =
                    restTemplate.postForEntity("/api/v1/accounts/phone-sms-auth", request, String.class);
            long elapsedNanos = System.nanoTime() - startNanos;

            if (measure) {
                samples.add(new Sample(elapsedNanos, resp.getStatusCode(), resp.getHeaders(), resp.getBody()));
            }
        }
        return samples;
    }

    private static void assertSuccessShape(List<Sample> samples) {
        assertThat(samples).allSatisfy(sample -> {
            assertThat(sample.status()).isEqualTo(HttpStatus.OK);
            assertThat(sample.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(sample.body()).isNotNull();
            assertThat(sample.body()).contains("\"accountId\"");
            assertThat(sample.body()).contains("\"accessToken\"");
            assertThat(sample.body()).contains("\"refreshToken\"");
            // No leak of branch-specific fields (e.g. "isNewUser" / "lastLoginAt")
            assertThat(sample.body()).doesNotContain("isNewUser").doesNotContain("lastLoginAt");
        });
    }

    private static void assertFailureShape(List<Sample> samples) {
        assertThat(samples).allSatisfy(sample -> {
            assertThat(sample.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(sample.body()).isNotNull();
            assertThat(sample.body()).contains("INVALID_CREDENTIALS");
        });
    }

    private static long p95Nanos(List<Sample> samples) {
        long[] sorted =
                samples.stream().mapToLong(Sample::elapsedNanos).sorted().toArray();
        // P95 of N=50 → index 47 (ceil(0.95 * 50) - 1)
        int idx = (int) Math.max(0, Math.ceil(0.95 * sorted.length) - 1);
        return sorted[idx];
    }

    private static long nanosToMs(long nanos) {
        return nanos / 1_000_000L;
    }

    private void seedActiveAccount(String phone) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            PhoneNumber phoneNumber = new PhoneNumber(phone);
            Account account = new Account(phoneNumber, now);
            AccountStateMachine.activate(account, now);
            Account saved = accountRepository.save(account);
            credentialRepository.save(new PhoneCredential(saved.id(), phoneNumber, now));
        });
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

    private enum Branch {
        REGISTERED,
        UNREGISTERED,
        WRONG_CODE
    }

    private record Sample(long elapsedNanos, HttpStatusCode status, HttpHeaders headers, String body) {}
}
