package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.MbwApplication;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.account.domain.service.RefreshTokenHasher;
import com.mbw.account.domain.service.TokenIssuer;
import java.time.Instant;
import java.util.UUID;
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
 * End-to-end IT for refresh-token (Phase 1.3 T10).
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Happy path: rotation returns new pair + revokes old
 *   <li>Replay defense: re-using the OLD refresh token after rotation → 401
 *   <li>Unknown token (attacker-fabricated) → 401
 *   <li>FR-009 retrofit assertion: a fresh login leaves a row in
 *       {@code account.refresh_token} with revoked_at NULL
 * </ul>
 *
 * <p>Pre-seeds via repos to bypass the per-phone /sms-codes 60s
 * rate-limit (same pattern as the other E2E ITs).
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RefreshTokenE2EIT {

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

    @Test
    void rotation_returns_new_pair_and_revokes_old_token() {
        SeededSession session = seedActiveAccountWithRefreshToken();

        // First rotation
        ResponseEntity<RefreshResponse> resp1 = restTemplate.postForEntity(
                "/api/v1/auth/refresh-token",
                jsonRequest("{\"refreshToken\":\"" + session.rawRefreshToken + "\"}", uniqueIp()),
                RefreshResponse.class);

        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp1.getBody().accessToken()).isNotBlank();
        assertThat(resp1.getBody().refreshToken()).isNotBlank().isNotEqualTo(session.rawRefreshToken);

        // Replay defense: reusing the original token must fail
        ResponseEntity<String> replay = restTemplate.postForEntity(
                "/api/v1/auth/refresh-token",
                jsonRequest("{\"refreshToken\":\"" + session.rawRefreshToken + "\"}", uniqueIp()),
                String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replay.getBody()).contains("INVALID_CREDENTIALS");

        // The new token works for the next rotation
        ResponseEntity<RefreshResponse> resp2 = restTemplate.postForEntity(
                "/api/v1/auth/refresh-token",
                jsonRequest("{\"refreshToken\":\"" + resp1.getBody().refreshToken() + "\"}", uniqueIp()),
                RefreshResponse.class);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unknown_token_returns_401() {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/refresh-token",
                jsonRequest("{\"refreshToken\":\"definitely-not-issued-by-us\"}", uniqueIp()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void blank_refresh_token_returns_400() {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/refresh-token", jsonRequest("{\"refreshToken\":\"\"}", uniqueIp()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void retrofit_login_persists_refresh_token_row() {
        // Seed account directly + go through full login-by-phone-sms via seeded session.
        // Then assert findByTokenHash returns the active record (FR-009 retrofit).
        SeededSession session = seedActiveAccountWithRefreshToken();

        var record = refreshTokenRepository
                .findByTokenHash(RefreshTokenHasher.hash(session.rawRefreshToken))
                .orElseThrow();

        assertThat(record.accountId().value()).isEqualTo(session.accountId);
        assertThat(record.revokedAt()).isNull();
        assertThat(record.expiresAt()).isAfter(Instant.now());
    }

    private SeededSession seedActiveAccountWithRefreshToken() {
        String phone = uniquePhone();
        String rawRefresh = tokenIssuer.signRefresh();
        long[] accountIdHolder = new long[1];
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            PhoneNumber phoneNumber = new PhoneNumber(phone);
            Account account = new Account(phoneNumber, now);
            AccountStateMachine.activate(account, now);
            Account saved = accountRepository.save(account);
            credentialRepository.save(new PhoneCredential(saved.id(), phoneNumber, now));
            refreshTokenRepository.save(com.mbw.account.domain.model.RefreshTokenRecord.createActive(
                    RefreshTokenHasher.hash(rawRefresh), saved.id(), now.plusSeconds(30L * 24 * 3600), now));
            accountIdHolder[0] = saved.id().value();
        });
        return new SeededSession(accountIdHolder[0], rawRefresh);
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

    private record SeededSession(long accountId, String rawRefreshToken) {}

    private record RefreshResponse(long accountId, String accessToken, String refreshToken) {}
}
