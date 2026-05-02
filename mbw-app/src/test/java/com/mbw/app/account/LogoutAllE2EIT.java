package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.MbwApplication;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.model.RefreshTokenRecord;
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
 * End-to-end IT for logout-all (Phase 1.4 T3).
 *
 * <p>Covers spec.md User Stories 1-3:
 *
 * <ul>
 *   <li>Multi-device: 3 active refresh tokens for the same account →
 *       /logout-all → all 3 are revoked + subsequent /refresh-token
 *       calls return 401
 *   <li>Auth failure modes: missing / malformed / invalid bearer →
 *       401 INVALID_CREDENTIALS
 *   <li>Idempotency: logout-all on an account with no active tokens →
 *       still 204
 * </ul>
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LogoutAllE2EIT {

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
    void logout_all_revokes_every_active_refresh_token_for_account() {
        Seeded session = seedAccountWithThreeRefreshTokens();
        String accessJwt = tokenIssuer.signAccess(new AccountId(session.accountId));

        ResponseEntity<Void> resp = restTemplate.exchange(
                "/api/v1/auth/logout-all",
                org.springframework.http.HttpMethod.POST,
                bearer(accessJwt, uniqueIp()),
                Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // All 3 refresh tokens must now fail
        for (String raw : session.rawRefreshTokens) {
            ResponseEntity<String> refreshResp = restTemplate.postForEntity(
                    "/api/v1/auth/refresh-token",
                    jsonRequest("{\"refreshToken\":\"" + raw + "\"}", uniqueIp()),
                    String.class);
            assertThat(refreshResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(refreshResp.getBody()).contains("INVALID_CREDENTIALS");
        }
    }

    @Test
    void logout_all_returns_204_even_when_account_has_no_active_tokens() {
        Seeded session = seedAccountWithThreeRefreshTokens();
        String accessJwt = tokenIssuer.signAccess(new AccountId(session.accountId));

        // First call revokes the 3 tokens
        ResponseEntity<Void> first = restTemplate.exchange(
                "/api/v1/auth/logout-all",
                org.springframework.http.HttpMethod.POST,
                bearer(accessJwt, uniqueIp()),
                Void.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Second call: 0 active tokens, but still 204 (idempotent)
        ResponseEntity<Void> second = restTemplate.exchange(
                "/api/v1/auth/logout-all",
                org.springframework.http.HttpMethod.POST,
                bearer(accessJwt, uniqueIp()),
                Void.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void logout_all_returns_401_when_no_authorization_header() {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/auth/logout-all",
                org.springframework.http.HttpMethod.POST,
                bearer(null, uniqueIp()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void logout_all_returns_401_when_bearer_token_malformed() {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/auth/logout-all",
                org.springframework.http.HttpMethod.POST,
                bearer("not-a-real-jwt", uniqueIp()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("INVALID_CREDENTIALS");
    }

    private Seeded seedAccountWithThreeRefreshTokens() {
        String phone = uniquePhone();
        long[] accountIdHolder = new long[1];
        String[] tokens = new String[3];
        for (int i = 0; i < 3; i++) {
            tokens[i] = tokenIssuer.signRefresh();
        }
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            PhoneNumber phoneNumber = new PhoneNumber(phone);
            Account account = new Account(phoneNumber, now);
            AccountStateMachine.activate(account, now);
            Account saved = accountRepository.save(account);
            credentialRepository.save(new PhoneCredential(saved.id(), phoneNumber, now));
            for (String raw : tokens) {
                refreshTokenRepository.save(RefreshTokenRecord.createActive(
                        RefreshTokenHasher.hash(raw), saved.id(), now.plusSeconds(30L * 24 * 3600), now));
            }
            accountIdHolder[0] = saved.id().value();
        });
        return new Seeded(accountIdHolder[0], tokens);
    }

    private static HttpEntity<Void> bearer(String accessToken, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) {
            headers.set("Authorization", "Bearer " + accessToken);
        }
        if (forwardedFor != null) {
            headers.set("X-Forwarded-For", forwardedFor);
        }
        return new HttpEntity<>(headers);
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

    private record Seeded(long accountId, String[] rawRefreshTokens) {}
}
