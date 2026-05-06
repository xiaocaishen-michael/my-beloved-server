package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

import com.mbw.MbwApplication;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.account.domain.service.RefreshTokenHasher;
import com.mbw.account.domain.service.TokenIssuer;
import com.mbw.shared.api.sms.SmsClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
 * End-to-end IT for the delete-account use case (per
 * {@code spec/account/delete-account/spec.md} T10).
 *
 * <p>Bootstraps the full {@link MbwApplication} context against real
 * Testcontainers (PG + Redis). {@link SmsClient} is replaced with a
 * Mockito {@code @MockBean} so the plaintext deletion code can be
 * captured from the mock invocation and submitted to the second endpoint
 * without a real SMS gateway.
 *
 * <p>Covered scenarios:
 *
 * <ul>
 *   <li>US1: full deletion flow (sendCode → delete → FROZEN + tokens revoked)
 *   <li>US2: auth failures (no header, invalid JWT, FROZEN account on sendCode)
 *   <li>US3: code failures (wrong code, no prior code sent)
 *   <li>US4: rate limit on sendCode endpoint
 *   <li>Body validation: malformed code format on delete endpoint
 * </ul>
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AccountDeletionE2EIT {

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

    /** Replaced by MockBean so the plaintext code can be captured. */
    @MockBean
    private SmsClient smsClient;

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

    // ── US1: happy deletion flow ──────────────────────────────────────────────

    @Test
    void full_deletion_flow_transitions_account_to_FROZEN_and_revokes_refresh_token() {
        String phone = uniquePhone();
        String ip = uniqueIp();
        AccountId[] idHolder = new AccountId[1];
        String rawRefreshToken = tokenIssuer.signRefresh();

        transactionTemplate.executeWithoutResult(txStatus -> {
            Instant now = Instant.now();
            PhoneNumber phoneNumber = new PhoneNumber(phone);
            Account account = new Account(phoneNumber, now);
            AccountStateMachine.activate(account, now);
            Account saved = accountRepository.save(account);
            credentialRepository.save(new PhoneCredential(saved.id(), phoneNumber, now));
            refreshTokenRepository.save(RefreshTokenRecord.createActive(
                    RefreshTokenHasher.hash(rawRefreshToken), saved.id(), now.plus(Duration.ofDays(30)), now));
            idHolder[0] = saved.id();
        });

        String accessJwt = tokenIssuer.signAccess(idHolder[0]);

        // Step 1: trigger SMS code — mock captures the plaintext
        ResponseEntity<Void> sendResp = restTemplate.exchange(
                "/api/v1/accounts/me/deletion-codes", HttpMethod.POST, bearer(accessJwt, ip), Void.class);
        assertThat(sendResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(smsClient).send(anyString(), anyString(), paramsCaptor.capture());
        String deletionCode = paramsCaptor.getValue().get("code");
        assertThat(deletionCode).matches("\\d{6}");

        // Step 2: submit code → account transitions ACTIVE → FROZEN
        ResponseEntity<Void> deleteResp = restTemplate.exchange(
                "/api/v1/accounts/me/deletion",
                HttpMethod.POST,
                bearerJson("{\"code\":\"" + deletionCode + "\"}", accessJwt, ip),
                Void.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify DB: account is FROZEN + freezeUntil ≈ now + 15 days
        Account reloaded = accountRepository.findById(idHolder[0]).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(AccountStatus.FROZEN);
        Instant expectedFreezeUntil = Instant.now().plus(Duration.ofDays(15));
        assertThat(reloaded.freezeUntil())
                .isBetween(expectedFreezeUntil.minusSeconds(10), expectedFreezeUntil.plusSeconds(10));

        // Verify: previously issued refresh token is revoked
        ResponseEntity<String> refreshResp = restTemplate.postForEntity(
                "/api/v1/auth/refresh-token", json("{\"refreshToken\":\"" + rawRefreshToken + "\"}", ip), String.class);
        assertThat(refreshResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── US2: auth failures on sendCode ────────────────────────────────────────

    @Test
    void sendCode_returns_401_AUTH_FAILED_when_no_authorization_header() {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/accounts/me/deletion-codes", HttpMethod.POST, noAuth(uniqueIp()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("AUTH_FAILED");
    }

    @Test
    void sendCode_returns_401_AUTH_FAILED_when_bearer_token_is_invalid() {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/accounts/me/deletion-codes",
                HttpMethod.POST,
                bearer("not-a-real-jwt", uniqueIp()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("AUTH_FAILED");
    }

    @Test
    void sendCode_returns_401_AUTH_FAILED_when_account_is_FROZEN() {
        // Seed a FROZEN account (simulates post-deletion state)
        String phone = uniquePhone();
        AccountId[] idHolder = new AccountId[1];

        transactionTemplate.executeWithoutResult(txStatus -> {
            Instant now = Instant.now();
            PhoneNumber phoneNumber = new PhoneNumber(phone);
            Account account = new Account(phoneNumber, now);
            AccountStateMachine.activate(account, now);
            AccountStateMachine.markFrozen(account, now.plus(Duration.ofDays(15)), now);
            Account saved = accountRepository.save(account);
            credentialRepository.save(new PhoneCredential(saved.id(), phoneNumber, now));
            idHolder[0] = saved.id();
        });

        String accessJwt = tokenIssuer.signAccess(idHolder[0]);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/accounts/me/deletion-codes", HttpMethod.POST, bearer(accessJwt, uniqueIp()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("AUTH_FAILED");
    }

    // ── US3: code failures on delete ─────────────────────────────────────────

    @Test
    void delete_returns_401_INVALID_DELETION_CODE_when_wrong_code_submitted() {
        AccountId accountId = seedActiveAccount(uniquePhone());
        String ip = uniqueIp();
        String accessJwt = tokenIssuer.signAccess(accountId);

        // Send code (so a record exists) but submit a wrong one
        restTemplate.exchange("/api/v1/accounts/me/deletion-codes", HttpMethod.POST, bearer(accessJwt, ip), Void.class);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/accounts/me/deletion",
                HttpMethod.POST,
                bearerJson("{\"code\":\"000000\"}", accessJwt, ip),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("INVALID_DELETION_CODE");
    }

    @Test
    void delete_returns_401_INVALID_DELETION_CODE_when_no_deletion_code_was_sent() {
        AccountId accountId = seedActiveAccount(uniquePhone());
        String accessJwt = tokenIssuer.signAccess(accountId);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/accounts/me/deletion",
                HttpMethod.POST,
                bearerJson("{\"code\":\"123456\"}", accessJwt, uniqueIp()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("INVALID_DELETION_CODE");
    }

    // ── US4: rate limiting ────────────────────────────────────────────────────

    @Test
    void sendCode_returns_429_when_called_twice_for_same_account_within_window() {
        AccountId accountId = seedActiveAccount(uniquePhone());
        String ip = uniqueIp();
        String accessJwt = tokenIssuer.signAccess(accountId);

        // First call: should succeed (capacity = 1 per 60s per account)
        ResponseEntity<Void> first = restTemplate.exchange(
                "/api/v1/accounts/me/deletion-codes", HttpMethod.POST, bearer(accessJwt, ip), Void.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Second call within the same 60s window: rate limited
        ResponseEntity<String> second = restTemplate.exchange(
                "/api/v1/accounts/me/deletion-codes", HttpMethod.POST, bearer(accessJwt, ip), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(second.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(second.getBody()).contains("RATE_LIMITED");
    }

    // ── Body validation ───────────────────────────────────────────────────────

    @Test
    void delete_returns_400_when_code_field_has_invalid_format() {
        AccountId accountId = seedActiveAccount(uniquePhone());
        String accessJwt = tokenIssuer.signAccess(accountId);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/accounts/me/deletion",
                HttpMethod.POST,
                bearerJson("{\"code\":\"abc123\"}", accessJwt, uniqueIp()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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
        if (accessToken != null) {
            headers.set("Authorization", "Bearer " + accessToken);
        }
        headers.set("X-Forwarded-For", forwardedFor);
        return new HttpEntity<>(headers);
    }

    private static HttpEntity<Void> noAuth(String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
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

    private static HttpEntity<String> json(String body, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
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
