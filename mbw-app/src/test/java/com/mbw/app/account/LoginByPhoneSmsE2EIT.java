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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * End-to-end IT for the login-by-phone-sms flow (T11).
 *
 * <p>Boots the full Spring Boot context against real PostgreSQL +
 * Redis containers. {@link SmsClient} is the only mocked bean — it
 * captures the plaintext code emitted on registration / login-SMS so
 * the test can replay it as the verification code on subsequent calls.
 *
 * <p>Covers spec.md User Stories 1 (happy path login after registration),
 * 2 (anti-enumeration: wrong code / unregistered phone fold to identical
 * 401), and 3 (FR-006 rate-limit enforcement). Concurrency (SC-002) is
 * exercised via 100 distinct accounts logging in in parallel. SC-001
 * P95 latency is gated by {@code LoginByPhoneSmsP95IT} (separate file,
 * tagged "slow"), per analysis.md A1.
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LoginByPhoneSmsE2EIT {

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
    void registered_phone_with_valid_login_code_returns_tokens() {
        // Pre-seed via repos so /sms-codes per-phone bucket is fresh
        String phone = uniquePhone();
        seedActiveAccount(phone);

        // Request a login-purpose code; SmsClient mock captures the code
        AtomicReference<String> capturedLoginCode = new AtomicReference<>();
        doAnswer(inv -> {
                    Map<String, String> params = inv.getArgument(2);
                    if (params.containsKey("code")) {
                        capturedLoginCode.set(params.get("code"));
                    }
                    return null;
                })
                .when(smsClient)
                .send(eq(phone), any(), any());

        ResponseEntity<Void> smsResp = restTemplate.postForEntity(
                "/api/v1/sms-codes",
                jsonRequest("{\"phone\":\"" + phone + "\",\"purpose\":\"LOGIN\"}", uniqueIp()),
                Void.class);
        assertThat(smsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capturedLoginCode.get()).matches("^\\d{6}$");

        ResponseEntity<LoginResponse> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login-by-phone-sms",
                jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"" + capturedLoginCode.get() + "\"}", uniqueIp()),
                LoginResponse.class);

        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResp.getBody().accountId()).isPositive();
        assertThat(loginResp.getBody().accessToken()).isNotBlank();
        assertThat(loginResp.getBody().refreshToken()).isNotBlank();
    }

    @Test
    void wrong_login_code_returns_401_INVALID_CREDENTIALS() {
        String phone = uniquePhone();
        seedActiveAccount(phone);

        doNothing().when(smsClient).send(any(), any(), any());
        // Request a fresh LOGIN code (consumes one) — but submit a wrong code
        restTemplate.postForEntity(
                "/api/v1/sms-codes",
                jsonRequest("{\"phone\":\"" + phone + "\",\"purpose\":\"LOGIN\"}", uniqueIp()),
                Void.class);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login-by-phone-sms",
                jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"000000\"}", uniqueIp()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void unregistered_phone_login_returns_401_INVALID_CREDENTIALS() {
        String phone = uniquePhone(); // never registered

        // Direct login attempt without a code request — typical attacker path
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login-by-phone-sms",
                jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"123456\"}", uniqueIp()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // FR-006 anti-enumeration: same code as wrong-code path
        assertThat(resp.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void sixth_login_attempt_within_24h_for_same_phone_returns_429() {
        // FR-006: login:<phone> 24h-5 rate limit
        String phone = uniquePhone();
        seedActiveAccount(phone);
        doNothing().when(smsClient).send(any(), any(), any());

        // 5 attempts pass; 6th is rate-limited
        for (int i = 1; i <= 5; i++) {
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    "/api/v1/auth/login-by-phone-sms",
                    jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"000000\"}", uniqueIp()),
                    String.class);
            assertThat(resp.getStatusCode())
                    .as("attempt " + i + " of 5 — phone bucket not yet exhausted")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> sixth = restTemplate.postForEntity(
                "/api/v1/auth/login-by-phone-sms",
                jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"000000\"}", uniqueIp()),
                String.class);
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(sixth.getHeaders().getFirst("Retry-After")).isNotNull();
    }

    @Test
    void hundred_distinct_phones_login_concurrently_no_errors() throws InterruptedException {
        // SC-002: 100 distinct accounts log in in parallel; 0 errors
        int n = 100;
        String[] phones = new String[n];
        String[] codes = new String[n];

        // Pre-seed accounts via repos + fetch login codes serially. Skipping
        // the /sms-codes register call avoids the per-phone 60s rate-limit
        // bucket, which would otherwise reject the second (LOGIN-purpose)
        // /sms-codes call below.
        for (int i = 0; i < n; i++) {
            phones[i] = uniquePhone();
            seedActiveAccount(phones[i]);
            String phone = phones[i];
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
            ResponseEntity<Void> r = restTemplate.postForEntity(
                    "/api/v1/sms-codes",
                    jsonRequest("{\"phone\":\"" + phone + "\",\"purpose\":\"LOGIN\"}", uniqueIp()),
                    Void.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            codes[i] = captured.get();
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
                            "/api/v1/auth/login-by-phone-sms",
                            jsonRequest(
                                    "{\"phone\":\"" + phones[idx] + "\",\"code\":\"" + codes[idx] + "\"}", uniqueIp()),
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
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finished).as("all 100 logins completed within 60s").isTrue();
        assertThat(successes.get())
                .as("100 distinct accounts logged in successfully")
                .isEqualTo(n);
        assertThat(failures.get()).isZero();
    }

    /**
     * Pre-seed an ACTIVE account directly through the domain repositories.
     * Bypasses {@code /sms-codes} so the per-phone 60s rate-limit bucket
     * stays empty, allowing the test to immediately request a fresh
     * LOGIN-purpose code for the same phone.
     */
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

    private record LoginResponse(long accountId, String accessToken, String refreshToken) {}
}
