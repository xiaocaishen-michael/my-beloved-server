package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.MbwApplication;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountSmsCode;
import com.mbw.account.domain.model.AccountSmsCodePurpose;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.AccountSmsCodeRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.account.domain.service.TokenIssuer;
import com.mbw.shared.api.sms.SmsClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
 * Cross-use-case anti-enumeration IT for the delete-account flow (per
 * {@code spec/account/delete-account/tasks.md} T12).
 *
 * <p>Asserts three properties:
 *
 * <ol>
 *   <li>The four AUTH_FAILED paths on {@code POST /me/deletion-codes}
 *       (no token / invalid token / FROZEN account / non-existent
 *       account) produce byte-identical 401 responses so a caller
 *       cannot tell which arm fired.
 *   <li>The four INVALID_DELETION_CODE paths on {@code POST /me/deletion}
 *       (code not found / wrong code / expired code / used code) produce
 *       byte-identical 401 responses.
 *   <li>The deletion error code ({@code INVALID_DELETION_CODE}) is
 *       distinct from the login error code ({@code INVALID_CREDENTIALS})
 *       so cross-endpoint confusion is impossible.
 * </ol>
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CrossUseCaseEnumerationDefenseIT {

    private static final HexFormat HEX = HexFormat.of();

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

    // ── T12a: 4 AUTH_FAILED paths on sendCode endpoint ───────────────────────

    @Test
    void auth_failure_paths_on_send_code_should_be_byte_identical_401() {
        // Path A: no Authorization header
        ResponseEntity<String> noHeader = restTemplate.exchange(
                "/api/v1/accounts/me/deletion-codes",
                HttpMethod.POST,
                new HttpEntity<>(new HttpHeaders()),
                String.class);

        // Path B: garbled Bearer token
        ResponseEntity<String> garbled = restTemplate.exchange(
                "/api/v1/accounts/me/deletion-codes", HttpMethod.POST, bearer("not.a.valid.jwt"), String.class);

        // Path C: valid JWT for a FROZEN account (AccountInactiveException → AUTH_FAILED)
        AccountId frozenId = seedFrozenAccount(uniquePhone());
        ResponseEntity<String> frozen = restTemplate.exchange(
                "/api/v1/accounts/me/deletion-codes",
                HttpMethod.POST,
                bearer(tokenIssuer.signAccess(frozenId)),
                String.class);

        // Path D: valid JWT for non-existent account id (AccountNotFoundException → AUTH_FAILED)
        ResponseEntity<String> unknown = restTemplate.exchange(
                "/api/v1/accounts/me/deletion-codes",
                HttpMethod.POST,
                bearer(tokenIssuer.signAccess(new AccountId(999_999_999L))),
                String.class);

        assertThat(noHeader.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(garbled.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(frozen.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String reference = noHeader.getBody();
        assertThat(reference).isNotBlank();
        assertThat(reference).contains("\"code\":\"AUTH_FAILED\"");
        assertThat(garbled.getBody()).isEqualTo(reference);
        assertThat(frozen.getBody()).isEqualTo(reference);
        assertThat(unknown.getBody()).isEqualTo(reference);
    }

    // ── T12b: 4 INVALID_DELETION_CODE paths on delete endpoint ───────────────

    @Test
    void deletion_code_failure_paths_should_be_byte_identical_401() {
        Instant now = Instant.now();

        // Path A: no code record exists at all
        AccountId accountA = seedActiveAccount(uniquePhone());
        ResponseEntity<String> notFound = submitCode("111111", tokenIssuer.signAccess(accountA));

        // Path B: active code exists but wrong plaintext submitted
        AccountId accountB = seedActiveAccount(uniquePhone());
        smsCodeRepository.save(AccountSmsCode.create(
                accountB,
                sha256Hex("999999"),
                now.plus(Duration.ofMinutes(10)),
                AccountSmsCodePurpose.DELETE_ACCOUNT,
                now));
        ResponseEntity<String> wrongCode = submitCode("000000", tokenIssuer.signAccess(accountB));

        // Path C: expired code — query filter (expiresAt > now) excludes it
        AccountId accountC = seedActiveAccount(uniquePhone());
        smsCodeRepository.save(AccountSmsCode.create(
                accountC,
                sha256Hex("654321"),
                now.minus(Duration.ofMinutes(5)),
                AccountSmsCodePurpose.DELETE_ACCOUNT,
                now));
        ResponseEntity<String> expired = submitCode("654321", tokenIssuer.signAccess(accountC));

        // Path D: code already used — submit the correct code once (succeeds), then again
        AccountId accountD = seedActiveAccount(uniquePhone());
        String plainD = "222222";
        smsCodeRepository.save(AccountSmsCode.create(
                accountD,
                sha256Hex(plainD),
                now.plus(Duration.ofMinutes(10)),
                AccountSmsCodePurpose.DELETE_ACCOUNT,
                now));
        String jwtD = tokenIssuer.signAccess(accountD);
        // First call freezes account and marks code used
        restTemplate.exchange(
                "/api/v1/accounts/me/deletion",
                HttpMethod.POST,
                bearerJson("{\"code\":\"" + plainD + "\"}", jwtD, uniqueIp()),
                Void.class);
        // Second call: usedAt IS NOT NULL → findActive returns empty → 401
        ResponseEntity<String> usedCode = submitCode(plainD, jwtD);

        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongCode.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(usedCode.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String reference = notFound.getBody();
        assertThat(reference).isNotBlank();
        assertThat(reference).contains("\"code\":\"INVALID_DELETION_CODE\"");
        assertThat(wrongCode.getBody()).isEqualTo(reference);
        assertThat(expired.getBody()).isEqualTo(reference);
        assertThat(usedCode.getBody()).isEqualTo(reference);
    }

    // ── cancel-deletion T8: 5 cancel 401 paths byte-identical ────────────────

    @Test
    void cancel_deletion_401_paths_should_be_byte_identical() {
        Instant now = Instant.now();

        // Path A: phone not registered
        ResponseEntity<String> notRegistered = postCancel(uniquePhone(), "111111");

        // Path B: account ACTIVE (cancel ineligible — phone-class branch dummy)
        String phoneB = uniquePhone();
        seedActiveAccount(phoneB);
        ResponseEntity<String> activeAcct = postCancel(phoneB, "111111");

        // Path C: account FROZEN with grace expired
        String phoneC = uniquePhone();
        seedFrozenAccount(phoneC, /* graceRemaining */ Duration.ofMillis(-1));
        ResponseEntity<String> frozenExpired = postCancel(phoneC, "111111");

        // Path D: FROZEN-in-grace but no active CANCEL_DELETION code
        String phoneD = uniquePhone();
        seedFrozenAccount(phoneD, Duration.ofDays(10));
        ResponseEntity<String> noActiveCode = postCancel(phoneD, "111111");

        // Path E: FROZEN-in-grace with active code but submit wrong plaintext
        String phoneE = uniquePhone();
        AccountId idE = seedFrozenAccount(phoneE, Duration.ofDays(10));
        smsCodeRepository.save(AccountSmsCode.create(
                idE,
                sha256Hex("999999"),
                now.plus(Duration.ofMinutes(10)),
                AccountSmsCodePurpose.CANCEL_DELETION,
                now));
        ResponseEntity<String> wrongCode = postCancel(phoneE, "000000");

        assertThat(notRegistered.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(activeAcct.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(frozenExpired.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(noActiveCode.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongCode.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String reference = notRegistered.getBody();
        assertThat(reference).isNotBlank();
        assertThat(reference).contains("\"code\":\"INVALID_CREDENTIALS\"");
        assertThat(activeAcct.getBody()).isEqualTo(reference);
        assertThat(frozenExpired.getBody()).isEqualTo(reference);
        assertThat(noActiveCode.getBody()).isEqualTo(reference);
        assertThat(wrongCode.getBody()).isEqualTo(reference);
    }

    // ── cancel-deletion T8: cancel INVALID_CREDENTIALS shape parity ──────────
    // Both endpoints share InvalidCredentialsException, so the 401 ProblemDetail
    // is structurally identical — same status / code / title / detail. The
    // {@code instance} field auto-fills with the request URI (Spring default)
    // and therefore differs by endpoint, but the URL is information the caller
    // already supplied; no anti-enumeration leak (plan.md § 反枚举).

    @Test
    void cancel_INVALID_CREDENTIALS_shape_should_match_phone_sms_auth_INVALID_CREDENTIALS() {
        ResponseEntity<String> cancelFailure = postCancel(uniquePhone(), "111111");
        ResponseEntity<String> phoneSmsAuthFailure = restTemplate.postForEntity(
                "/api/v1/accounts/phone-sms-auth",
                jsonRequest("{\"phone\":\"" + uniquePhone() + "\",\"code\":\"999999\"}", uniqueIp()),
                String.class);

        assertThat(cancelFailure.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(phoneSmsAuthFailure.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Strip the instance field (which Spring auto-fills with the request
        // URI) before comparing — the rest of the body must be byte-identical.
        String cancelBody = stripInstanceField(cancelFailure.getBody());
        String phoneSmsAuthBody = stripInstanceField(phoneSmsAuthFailure.getBody());

        assertThat(cancelBody).contains("\"code\":\"INVALID_CREDENTIALS\"");
        assertThat(phoneSmsAuthBody).contains("\"code\":\"INVALID_CREDENTIALS\"");
        assertThat(cancelBody)
                .as("status / title / detail / code byte-identical across endpoints")
                .isEqualTo(phoneSmsAuthBody);
    }

    private static String stripInstanceField(String body) {
        // Removes "instance":"..." key/value (with optional surrounding comma)
        // from a ProblemDetail JSON. Order-tolerant.
        return body.replaceAll(",\"instance\":\"[^\"]*\"", "").replaceAll("\"instance\":\"[^\"]*\",", "");
    }

    // ── T12c: deletion error code distinct from login error code ─────────────

    @Test
    void deletion_error_code_is_distinct_from_login_invalid_credentials() {
        // Login with a wrong code (unique phone so rate-limit bucket is fresh)
        ResponseEntity<String> loginFailure = restTemplate.postForEntity(
                "/api/v1/accounts/phone-sms-auth",
                jsonRequest("{\"phone\":\"" + uniquePhone() + "\",\"code\":\"999999\"}", uniqueIp()),
                String.class);

        // Deletion with no code record → INVALID_DELETION_CODE
        AccountId accountId = seedActiveAccount(uniquePhone());
        ResponseEntity<String> deleteFailure = submitCode("999999", tokenIssuer.signAccess(accountId));

        assertThat(loginFailure.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(deleteFailure.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(loginFailure.getBody()).contains("INVALID_CREDENTIALS");
        assertThat(deleteFailure.getBody()).contains("INVALID_DELETION_CODE");
        assertThat(loginFailure.getBody()).isNotEqualTo(deleteFailure.getBody());
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

    private AccountId seedFrozenAccount(String phone) {
        return seedFrozenAccount(phone, Duration.ofDays(15));
    }

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

    private ResponseEntity<String> postCancel(String phone, String code) {
        return restTemplate.exchange(
                "/api/v1/auth/cancel-deletion",
                HttpMethod.POST,
                jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}", uniqueIp()),
                String.class);
    }

    private ResponseEntity<String> submitCode(String code, String accessJwt) {
        return restTemplate.exchange(
                "/api/v1/accounts/me/deletion",
                HttpMethod.POST,
                bearerJson("{\"code\":\"" + code + "\"}", accessJwt, uniqueIp()),
                String.class);
    }

    private static HttpEntity<Void> bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        return new HttpEntity<>(headers);
    }

    private static HttpEntity<String> bearerJson(String body, String accessToken, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + accessToken);
        headers.set("X-Forwarded-For", forwardedFor);
        return new HttpEntity<>(body, headers);
    }

    private static HttpEntity<String> jsonRequest(String body, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        return new HttpEntity<>(body, headers);
    }

    private static String sha256Hex(String input) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
