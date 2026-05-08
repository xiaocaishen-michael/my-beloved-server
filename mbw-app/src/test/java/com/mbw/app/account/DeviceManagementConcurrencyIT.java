package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.MbwApplication;
import com.mbw.account.api.dto.DeviceType;
import com.mbw.account.api.dto.LoginMethod;
import com.mbw.account.api.event.DeviceRevokedEvent;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.DeviceId;
import com.mbw.account.domain.model.IpAddress;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.model.RefreshTokenRecordId;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.account.domain.service.RefreshTokenHasher;
import com.mbw.account.domain.service.TokenIssuer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
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
 * Concurrency / atomicity IT for device-management T16.
 *
 * <p>Two scenarios:
 *
 * <ul>
 *   <li>5 threads racing to revoke the same recordId — exactly one
 *       atomic UPDATE wins (FR-003 / SC-003). All 5 return 200; only
 *       one DeviceRevokedEvent is published (verified via test-side
 *       {@code @EventListener} counter).
 *   <li>List + refresh-token rotation interleave on the same account —
 *       neither blocks nor deadlocks the other.
 * </ul>
 *
 * <p>Outbox-failure rollback is covered by the unit test
 * {@code RevokeDeviceUseCaseTest.should_propagate_publish_failure_so_outer_transaction_rolls_back};
 * the IT path requires spying an interface bean which Spring boot test
 * cannot instantiate cleanly.
 */
@SpringBootTest(
        classes = {MbwApplication.class, DeviceManagementConcurrencyIT.EventCaptureConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DeviceManagementConcurrencyIT {

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
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private TokenIssuer tokenIssuer;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DeviceRevokedEventCounter eventCounter;

    @BeforeEach
    void resetCounter() {
        eventCounter.reset();
    }

    @Test
    void should_only_one_succeed_when_same_device_revoked_5_times_concurrently() throws InterruptedException {
        Seeded session = seedAccountWithTwoDevices();
        String accessJwt = tokenIssuer.signAccess(session.accountId, session.currentDeviceId);

        int threads = 5;
        AtomicInteger ok200 = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ResponseEntity<Void> resp = bearerDelete(
                            "/api/v1/auth/devices/" + session.targetRecordId.value(), accessJwt, uniqueIp());
                    if (resp.getStatusCode() == HttpStatus.OK) {
                        ok200.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    other.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(ok200.get())
                .as("all 5 must return 200 (1 success + 4 idempotent)")
                .isEqualTo(5);
        assertThat(other.get()).isZero();

        // Atomic UPDATE … WHERE revoked_at IS NULL means only one publish fired.
        assertThat(eventCounter.count())
                .as("exactly one DeviceRevokedEvent across the 5-way race")
                .isEqualTo(1);

        RefreshTokenRecord reloaded =
                refreshTokenRepository.findById(session.targetRecordId).orElseThrow();
        assertThat(reloaded.revokedAt()).isNotNull();
    }

    @Test
    void list_and_rotation_should_not_deadlock_on_same_account() throws InterruptedException {
        Seeded session = seedAccountWithTwoDevices();
        String accessJwt = tokenIssuer.signAccess(session.accountId, session.currentDeviceId);
        String rawRefresh = session.currentRawRefreshToken;

        AtomicInteger listOk = new AtomicInteger();
        AtomicInteger rotateOk = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        pool.submit(() -> {
            try {
                start.await();
                ResponseEntity<String> resp = bearerGet("/api/v1/auth/devices?page=0&size=10", accessJwt, uniqueIp());
                if (resp.getStatusCode() == HttpStatus.OK) {
                    listOk.incrementAndGet();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                ResponseEntity<String> resp =
                        postJson("/api/v1/auth/refresh-token", "{\"refreshToken\":\"" + rawRefresh + "\"}", uniqueIp());
                if (resp.getStatusCode() == HttpStatus.OK) {
                    rotateOk.incrementAndGet();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finished).as("list + rotation must not deadlock").isTrue();
        assertThat(listOk.get()).isEqualTo(1);
        assertThat(rotateOk.get()).isEqualTo(1);
    }

    // ── Test-side event capture ───────────────────────────────────────────────

    @TestConfiguration
    static class EventCaptureConfig {

        @Bean
        DeviceRevokedEventCounter deviceRevokedEventCounter() {
            return new DeviceRevokedEventCounter();
        }
    }

    static class DeviceRevokedEventCounter {
        private final AtomicInteger count = new AtomicInteger();

        @EventListener
        void on(DeviceRevokedEvent event) {
            count.incrementAndGet();
        }

        int count() {
            return count.get();
        }

        void reset() {
            count.set(0);
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private Seeded seedAccountWithTwoDevices() {
        return transactionTemplate.execute(status -> {
            String phone = uniquePhone();
            Account account = new Account(new PhoneNumber(phone), Instant.now());
            AccountStateMachine.activate(account, Instant.now());
            Account saved = accountRepository.save(account);
            credentialRepository.save(new PhoneCredential(saved.id(), new PhoneNumber(phone), Instant.now()));

            DeviceId currentDevice = new DeviceId("11111111-2222-4333-8444-555566667777");
            DeviceId targetDevice = new DeviceId("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeffff");
            Instant now = Instant.now();
            Instant expiresAt = now.plus(Duration.ofDays(30));

            String currentRaw = tokenIssuer.signRefresh();
            RefreshTokenRecord currentRow = refreshTokenRepository.save(RefreshTokenRecord.createActive(
                    RefreshTokenHasher.hash(currentRaw),
                    saved.id(),
                    currentDevice,
                    /* deviceName */ null,
                    DeviceType.PHONE,
                    IpAddress.ofNullable("203.0.113.10"),
                    LoginMethod.PHONE_SMS,
                    expiresAt,
                    now));
            RefreshTokenRecord targetRow = refreshTokenRepository.save(RefreshTokenRecord.createActive(
                    RefreshTokenHasher.hash(tokenIssuer.signRefresh()),
                    saved.id(),
                    targetDevice,
                    /* deviceName */ null,
                    DeviceType.DESKTOP,
                    IpAddress.ofNullable("203.0.113.20"),
                    LoginMethod.PHONE_SMS,
                    expiresAt,
                    now));

            return new Seeded(saved.id(), currentDevice, targetRow.id(), currentRow.id(), currentRaw);
        });
    }

    private ResponseEntity<Void> bearerDelete(String path, String accessJwt, String ip) {
        HttpHeaders headers = bearerHeaders(accessJwt, ip);
        return restTemplate.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
    }

    private ResponseEntity<String> bearerGet(String path, String accessJwt, String ip) {
        HttpHeaders headers = bearerHeaders(accessJwt, ip);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> postJson(String path, String body, String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Forwarded-For", ip);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static HttpHeaders bearerHeaders(String accessJwt, String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessJwt);
        headers.add("X-Forwarded-For", ip);
        return headers;
    }

    private static String uniquePhone() {
        long suffix = Math.abs(UUID.randomUUID().getMostSignificantBits()) % 10_000_000_000L;
        return "+8613" + String.format("%010d", suffix).substring(1);
    }

    private static String uniqueIp() {
        long t = System.nanoTime();
        int b3 = (int) (t & 0xFF);
        return "203.0.113." + Math.max(1, b3 % 254);
    }

    private record Seeded(
            AccountId accountId,
            DeviceId currentDeviceId,
            RefreshTokenRecordId targetRecordId,
            RefreshTokenRecordId currentRecordId,
            String currentRawRefreshToken) {}
}
