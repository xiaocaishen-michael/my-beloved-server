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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * End-to-end IT for login-by-password (P1.2 T5).
 *
 * <p>Boots the full Spring Boot context against PG + Redis containers.
 * Accounts are pre-seeded directly via repositories (faster + bypasses
 * the per-phone /sms-codes 60s rate-limit). The real
 * {@link PasswordHasher} bean is autowired so BCrypt verify runs
 * end-to-end at production cost.
 *
 * <p>Covers spec.md User Stories 1-4 + SC-002 (100 distinct accounts
 * concurrent login) + SC-004 (login:phone + auth:ip rate limits). SC-001
 * P95 stays in {@code LoginByPasswordTimingDefenseIT} (slow-tagged).
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LoginByPasswordE2EIT {

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
    void registered_phone_with_correct_password_returns_tokens() {
        String phone = uniquePhone();
        seedActiveAccountWithPassword(phone, VALID_PASSWORD);

        ResponseEntity<LoginResponse> resp = restTemplate.postForEntity(
                "/api/v1/auth/login-by-password",
                jsonRequest("{\"phone\":\"" + phone + "\",\"password\":\"" + VALID_PASSWORD + "\"}", uniqueIp()),
                LoginResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().accountId()).isPositive();
        assertThat(resp.getBody().accessToken()).isNotBlank();
        assertThat(resp.getBody().refreshToken()).isNotBlank();
    }

    @Test
    void wrong_password_returns_401_INVALID_CREDENTIALS() {
        String phone = uniquePhone();
        seedActiveAccountWithPassword(phone, VALID_PASSWORD);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login-by-password",
                jsonRequest("{\"phone\":\"" + phone + "\",\"password\":\"" + WRONG_PASSWORD + "\"}", uniqueIp()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void unregistered_phone_returns_401_INVALID_CREDENTIALS() {
        String phone = uniquePhone(); // never seeded

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login-by-password",
                jsonRequest("{\"phone\":\"" + phone + "\",\"password\":\"" + VALID_PASSWORD + "\"}", uniqueIp()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // FR-009 anti-enumeration — same code as wrong-password path
        assertThat(resp.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void account_without_password_set_returns_401_INVALID_CREDENTIALS() {
        String phone = uniquePhone();
        seedActiveAccountNoPassword(phone);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login-by-password",
                jsonRequest("{\"phone\":\"" + phone + "\",\"password\":\"" + VALID_PASSWORD + "\"}", uniqueIp()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // FR-009 anti-enumeration — registered-but-no-password indistinguishable
        // from unregistered + wrong-password
        assertThat(resp.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void sixth_login_attempt_within_24h_for_same_phone_returns_429() {
        // FR-007: login:<phone> 24h-5 rate limit (shared with login-by-phone-sms)
        String phone = uniquePhone();
        seedActiveAccountWithPassword(phone, VALID_PASSWORD);

        // 5 failed attempts pass through to 401; 6th hits the limit
        for (int i = 1; i <= 5; i++) {
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    "/api/v1/auth/login-by-password",
                    jsonRequest("{\"phone\":\"" + phone + "\",\"password\":\"" + WRONG_PASSWORD + "\"}", uniqueIp()),
                    String.class);
            assertThat(resp.getStatusCode())
                    .as("attempt " + i + " of 5 — phone bucket not yet exhausted")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> sixth = restTemplate.postForEntity(
                "/api/v1/auth/login-by-password",
                jsonRequest("{\"phone\":\"" + phone + "\",\"password\":\"" + WRONG_PASSWORD + "\"}", uniqueIp()),
                String.class);
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(sixth.getHeaders().getFirst("Retry-After")).isNotNull();
    }

    @Test
    void hundred_distinct_phones_login_concurrently_no_errors() throws InterruptedException {
        // SC-002: 100 distinct accounts log in in parallel; 0 errors
        int n = 100;
        String[] phones = new String[n];
        for (int i = 0; i < n; i++) {
            phones[i] = uniquePhone();
            seedActiveAccountWithPassword(phones[i], VALID_PASSWORD);
        }

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        ExecutorService pool = Executors.newFixedThreadPool(20);

        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ResponseEntity<LoginResponse> resp = restTemplate.postForEntity(
                            "/api/v1/auth/login-by-password",
                            jsonRequest(
                                    "{\"phone\":\"" + phones[idx] + "\",\"password\":\"" + VALID_PASSWORD + "\"}",
                                    uniqueIp()),
                            LoginResponse.class);
                    if (resp.getStatusCode() == HttpStatus.OK) {
                        successes.incrementAndGet();
                    } else {
                        failures.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(120, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finished).as("all 100 logins completed within 120s").isTrue();
        assertThat(successes.get())
                .as("100 distinct accounts logged in successfully")
                .isEqualTo(n);
        assertThat(failures.get()).isZero();
    }

    private void seedActiveAccountWithPassword(String phone, String plaintext) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            PhoneNumber phoneNumber = new PhoneNumber(phone);
            Account account = new Account(phoneNumber, now);
            AccountStateMachine.activate(account, now);
            Account saved = accountRepository.save(account);
            credentialRepository.save(new PhoneCredential(saved.id(), phoneNumber, now));
            PasswordHash hash = passwordHasher.hash(plaintext);
            credentialRepository.save(new PasswordCredential(saved.id(), hash, now));
        });
    }

    private void seedActiveAccountNoPassword(String phone) {
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

    private record LoginResponse(long accountId, String accessToken, String refreshToken) {}
}
