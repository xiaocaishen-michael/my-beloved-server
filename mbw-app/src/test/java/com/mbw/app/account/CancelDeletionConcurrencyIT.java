package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.mbw.MbwApplication;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountSmsCodePurpose;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.AccountSmsCodeRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.account.domain.service.TokenIssuer;
import com.mbw.shared.api.sms.SmsClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 * Concurrency and atomicity IT for the cancel-deletion use case (per
 * {@code spec/account/cancel-deletion/spec.md} SC-007 + FR-006, T7).
 *
 * <p>Three scenarios:
 *
 * <ul>
 *   <li>Five threads racing to submit the same CANCEL_DELETION code —
 *       exactly one must win the FROZEN → ACTIVE transition. The
 *       {@code markUsed} UPDATE holds a PG row lock until commit; later
 *       threads either see {@code used_at NOT NULL} (so the
 *       {@code findActive} pre-filter excludes the row) or, after
 *       commit, see status {@code ACTIVE} which makes the use case bail
 *       at the phone-class branch.
 *   <li>{@link TokenIssuer#signAccess} fails inside the transaction —
 *       all prior writes (markUsed, markActiveFromFrozen, save, outbox
 *       event) must roll back; account stays {@code FROZEN}, code stays
 *       active.
 *   <li>{@link RefreshTokenRepository#save} fails inside the transaction
 *       — same atomicity guarantee.
 * </ul>
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CancelDeletionConcurrencyIT {

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

    @MockBean
    private SmsClient smsClient;

    @SpyBean
    private TokenIssuer tokenIssuer;

    @SpyBean
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CredentialRepository credentialRepository;

    @Autowired
    private AccountSmsCodeRepository smsCodeRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetMocks() {
        reset(smsClient);
    }

    @AfterEach
    void resetSpies() {
        reset(tokenIssuer);
        reset(refreshTokenRepository);
    }

    // ── SC-007: 5 concurrent cancels — exactly 1 wins ────────────────────────

    @Test
    void should_only_one_succeed_when_same_phone_code_submitted_5_times_concurrently() throws InterruptedException {
        String phone = uniquePhone();
        AccountId accountId = seedFrozenAccount(phone, Duration.ofDays(10));
        String ip = uniqueIp();

        // Send code (capacity 1/60s for phone-tier on /sms-codes is fine — single send)
        postJson("/api/v1/auth/cancel-deletion/sms-codes", body("phone", phone), ip);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(smsClient).send(anyString(), anyString(), captor.capture());
        String code = captor.getValue().get("code");

        int threadCount = 5;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ResponseEntity<String> resp =
                            postJson("/api/v1/auth/cancel-deletion", body("phone", phone, "code", code), uniqueIp());
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
        assertThat(done.await(30, TimeUnit.SECONDS))
                .as("all threads completed within 30s")
                .isTrue();
        pool.shutdown();

        assertThat(successes.get())
                .as("exactly one cancel transition wins under concurrency")
                .isEqualTo(1);
        assertThat(failures.get()).as("the other 4 lose").isEqualTo(4);

        // Final state: ACTIVE + freezeUntil null
        Account reloaded = accountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(reloaded.freezeUntil()).isNull();
    }

    // ── FR-006: TokenIssuer failure → full rollback ──────────────────────────

    @Test
    void should_keep_state_unchanged_when_TokenIssuer_fails_in_transaction() {
        String phone = uniquePhone();
        AccountId accountId = seedFrozenAccount(phone, Duration.ofDays(10));
        String ip = uniqueIp();

        postJson("/api/v1/auth/cancel-deletion/sms-codes", body("phone", phone), ip);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(smsClient).send(anyString(), anyString(), captor.capture());
        String code = captor.getValue().get("code");

        // Make TokenIssuer.signAccess throw — @Transactional(rollbackFor=Throwable)
        // on CancelDeletionUseCase.execute rolls back markUsed + status transition + outbox
        doThrow(new RuntimeException("test-induced token signing failure"))
                .when(tokenIssuer)
                .signAccess(any());

        ResponseEntity<String> resp = postJson("/api/v1/auth/cancel-deletion", body("phone", phone, "code", code), ip);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("cancel must fail when TokenIssuer throws")
                .isFalse();

        // Account remains FROZEN with freezeUntil intact
        Account reloaded = accountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.status()).as("account status rolled back to FROZEN").isEqualTo(AccountStatus.FROZEN);
        assertThat(reloaded.freezeUntil())
                .as("freezeUntil rolled back to non-null")
                .isNotNull();

        // SMS code remains active (usedAt null, rolled back)
        assertThat(smsCodeRepository.findActiveByPurposeAndAccountId(
                        AccountSmsCodePurpose.CANCEL_DELETION, accountId, Instant.now()))
                .as("SMS code still active (usedAt IS NULL) after rollback")
                .isPresent();
    }

    // ── FR-006: refresh_token persist failure → full rollback ────────────────

    @Test
    void should_keep_state_unchanged_when_refresh_token_persist_fails() {
        String phone = uniquePhone();
        AccountId accountId = seedFrozenAccount(phone, Duration.ofDays(10));
        String ip = uniqueIp();

        postJson("/api/v1/auth/cancel-deletion/sms-codes", body("phone", phone), ip);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(smsClient).send(anyString(), anyString(), captor.capture());
        String code = captor.getValue().get("code");

        doThrow(new RuntimeException("test-induced DB failure"))
                .when(refreshTokenRepository)
                .save(any());

        ResponseEntity<String> resp = postJson("/api/v1/auth/cancel-deletion", body("phone", phone, "code", code), ip);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("cancel must fail when refresh_token persist throws")
                .isFalse();

        Account reloaded = accountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(AccountStatus.FROZEN);
        assertThat(reloaded.freezeUntil()).isNotNull();

        assertThat(smsCodeRepository.findActiveByPurposeAndAccountId(
                        AccountSmsCodePurpose.CANCEL_DELETION, accountId, Instant.now()))
                .isPresent();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private AccountId seedFrozenAccount(String phone, Duration graceRemaining) {
        AccountId[] holder = new AccountId[1];
        transactionTemplate.executeWithoutResult(txStatus -> {
            Instant now = Instant.now();
            PhoneNumber phoneNumber = new PhoneNumber(phone);
            Account account = new Account(phoneNumber, now);
            AccountStateMachine.activate(account, now);
            AccountStateMachine.markFrozen(account, now.plus(graceRemaining), now);
            Account saved = accountRepository.save(account);
            credentialRepository.save(new PhoneCredential(saved.id(), phoneNumber, now));
            holder[0] = saved.id();
        });
        return holder[0];
    }

    private ResponseEntity<String> postJson(String path, String body, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static String body(String k, String v) {
        return "{\"" + k + "\":\"" + v + "\"}";
    }

    private static String body(String k1, String v1, String k2, String v2) {
        return "{\"" + k1 + "\":\"" + v1 + "\",\"" + k2 + "\":\"" + v2 + "\"}";
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
