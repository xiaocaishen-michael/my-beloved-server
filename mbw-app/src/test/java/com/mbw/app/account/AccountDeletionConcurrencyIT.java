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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
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
 * Concurrency and atomicity IT for the delete-account use case (per
 * {@code spec/account/delete-account/spec.md} SC-007 + FR-006, T11).
 *
 * <p>Three scenarios:
 *
 * <ul>
 *   <li>Five threads racing to submit the same deletion code — exactly
 *       one must win. The {@code markUsed} UPDATE holds a PG row lock
 *       until commit; subsequent threads reload the account in READ
 *       COMMITTED and see {@code FROZEN}, so {@code markFrozen} throws
 *       and their transactions roll back.
 *   <li>Two devices racing for the sendCode endpoint — the per-account
 *       Bucket4j token (capacity=1/60s) admits exactly one; the second
 *       gets 429.
 *   <li>Transaction rollback when {@code revokeAllForAccount} fails —
 *       account must remain {@code ACTIVE} and the SMS code must remain
 *       unused (FR-006 atomicity guarantee).
 * </ul>
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AccountDeletionConcurrencyIT {

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
    private TokenIssuer tokenIssuer;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void resetSpy() {
        reset(refreshTokenRepository);
    }

    // ── SC-007: 5 concurrent deletes — exactly 1 wins ────────────────────────

    @Test
    void should_only_one_succeed_when_same_code_submitted_5_times_concurrently() throws InterruptedException {
        AccountId accountId = seedActiveAccount(uniquePhone());
        String accessJwt = tokenIssuer.signAccess(accountId);

        restTemplate.exchange(
                "/api/v1/accounts/me/deletion-codes", HttpMethod.POST, bearer(accessJwt, uniqueIp()), Void.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(smsClient).send(anyString(), anyString(), captor.capture());
        String deletionCode = captor.getValue().get("code");

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
                    ResponseEntity<Void> resp = restTemplate.exchange(
                            "/api/v1/accounts/me/deletion",
                            HttpMethod.POST,
                            bearerJson("{\"code\":\"" + deletionCode + "\"}", accessJwt, uniqueIp()),
                            Void.class);
                    if (resp.getStatusCode() == HttpStatus.NO_CONTENT) {
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
                .as("exactly one deletion wins under concurrency")
                .isEqualTo(1);
        assertThat(failures.get()).as("the other 4 lose").isEqualTo(4);
    }

    // ── 2 concurrent sendCode requests — 1 succeeds, 1 gets 429 ─────────────

    @Test
    void should_handle_two_devices_racing_for_deletion_codes_endpoint() throws InterruptedException {
        AccountId accountId = seedActiveAccount(uniquePhone());
        String accessJwt = tokenIssuer.signAccess(accountId);

        int threadCount = 2;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ResponseEntity<Void> resp = restTemplate.exchange(
                            "/api/v1/accounts/me/deletion-codes",
                            HttpMethod.POST,
                            bearer(accessJwt, uniqueIp()),
                            Void.class);
                    if (resp.getStatusCode() == HttpStatus.NO_CONTENT) {
                        successes.incrementAndGet();
                    } else if (resp.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                        rateLimited.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS))
                .as("both threads completed within 30s")
                .isTrue();
        pool.shutdown();

        assertThat(successes.get())
                .as("exactly one sendCode succeeds per 60s window")
                .isEqualTo(1);
        assertThat(rateLimited.get()).as("the other is rate-limited (429)").isEqualTo(1);
    }

    // ── FR-006: revokeAllForAccount failure → full rollback ──────────────────

    @Test
    void should_keep_all_state_unchanged_when_revokeAllForAccount_fails() {
        AccountId accountId = seedActiveAccount(uniquePhone());
        String accessJwt = tokenIssuer.signAccess(accountId);
        String ip = uniqueIp();

        // Send code and capture the plaintext before configuring the spy
        restTemplate.exchange("/api/v1/accounts/me/deletion-codes", HttpMethod.POST, bearer(accessJwt, ip), Void.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(smsClient).send(anyString(), anyString(), captor.capture());
        String deletionCode = captor.getValue().get("code");

        // Make revokeAllForAccount throw — the @Transactional(rollbackFor=Throwable)
        // boundary on DeleteAccountUseCase.execute will roll back all prior writes
        doThrow(new RuntimeException("test-induced DB failure"))
                .when(refreshTokenRepository)
                .revokeAllForAccount(any(), any());

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/accounts/me/deletion",
                HttpMethod.POST,
                bearerJson("{\"code\":\"" + deletionCode + "\"}", accessJwt, ip),
                String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("delete must fail when revokeAllForAccount throws")
                .isFalse();

        // Account status must be rolled back to ACTIVE
        Account reloaded = accountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.status()).as("account status rolled back to ACTIVE").isEqualTo(AccountStatus.ACTIVE);
        assertThat(reloaded.freezeUntil()).as("freezeUntil rolled back to null").isNull();

        // SMS code must still be active (usedAt IS NULL rolled back)
        assertThat(smsCodeRepository.findActiveByPurposeAndAccountId(
                        AccountSmsCodePurpose.DELETE_ACCOUNT, accountId, Instant.now()))
                .as("SMS code still active (usedAt IS NULL) after transaction rollback")
                .isPresent();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private AccountId seedActiveAccount(String phone) {
        AccountId[] holder = new AccountId[1];
        transactionTemplate.executeWithoutResult(txStatus -> {
            Instant now = Instant.now();
            PhoneNumber phoneNumber = new PhoneNumber(phone);
            Account account = new Account(phoneNumber, now);
            AccountStateMachine.activate(account, now);
            Account saved = accountRepository.save(account);
            credentialRepository.save(new PhoneCredential(saved.id(), phoneNumber, now));
            holder[0] = saved.id();
        });
        return holder[0];
    }

    private static HttpEntity<Void> bearer(String accessToken, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        headers.set("X-Forwarded-For", forwardedFor);
        return new HttpEntity<>(headers);
    }

    private static HttpEntity<String> bearerJson(String body, String accessToken, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + accessToken);
        headers.set("X-Forwarded-For", forwardedFor);
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
