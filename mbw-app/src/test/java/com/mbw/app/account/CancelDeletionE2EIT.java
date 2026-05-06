package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.mbw.MbwApplication;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.shared.api.sms.SmsClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * End-to-end IT for the cancel-deletion use case (per
 * {@code spec/account/cancel-deletion/spec.md} T6).
 *
 * <p>Public unauthed endpoints — no Bearer JWT path. Bootstraps the
 * full {@link MbwApplication} context against real Testcontainers
 * (PG + Redis). {@link SmsClient} is replaced with a Mockito
 * {@code @MockBean} so the plaintext CANCEL_DELETION code can be
 * captured from the mock invocation and submitted to the cancel
 * endpoint without a real SMS gateway.
 *
 * <p>Covered scenarios (US1-US4 from spec):
 *
 * <ul>
 *   <li>US1 happy: FROZEN-in-grace → submit code → ACTIVE + new tokens
 *   <li>US2 anti-enum on /sms-codes: 4 ineligible phone classes return
 *       200 silently (no SMS dispatched)
 *   <li>US3 code errors: wrong / no-prior-code / already-used → 401
 *   <li>US4 grace expired: /sms-codes 200 but no SMS, /cancel 401
 *   <li>Body validation: 400 on malformed phone or code
 * </ul>
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CancelDeletionE2EIT {

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
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetMocks() {
        reset(smsClient);
    }

    // ── US1: happy flow ───────────────────────────────────────────────────────

    @Test
    void full_cancel_flow_transitions_FROZEN_to_ACTIVE_and_issues_new_tokens() {
        String phone = uniquePhone();
        String ip = uniqueIp();
        AccountId accountId = seedFrozenAccount(phone, Duration.ofDays(10));

        // Step 1: send SMS code — mock captures plaintext
        ResponseEntity<String> sendResp = postJson("/api/v1/auth/cancel-deletion/sms-codes", body("phone", phone), ip);
        assertThat(sendResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(smsClient).send(anyString(), anyString(), paramsCaptor.capture());
        String code = paramsCaptor.getValue().get("code");
        assertThat(code).matches("\\d{6}");

        // Step 2: submit code → FROZEN → ACTIVE + LoginResponse
        ResponseEntity<String> cancelResp =
                postJson("/api/v1/auth/cancel-deletion", body("phone", phone, "code", code), ip);
        assertThat(cancelResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelResp.getBody())
                .contains("\"accountId\"")
                .contains("\"accessToken\"")
                .contains("\"refreshToken\"");

        // Verify DB: FROZEN → ACTIVE, freezeUntil cleared
        Account reloaded = accountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(reloaded.freezeUntil()).isNull();
    }

    // ── US2: anti-enumeration on /sms-codes — 4 ineligible classes ────────────

    @Test
    void sms_codes_returns_200_silently_when_phone_not_registered() {
        ResponseEntity<Void> resp =
                postJsonVoid("/api/v1/auth/cancel-deletion/sms-codes", body("phone", uniquePhone()), uniqueIp());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(smsClient, never()).send(anyString(), anyString(), any());
    }

    @Test
    void sms_codes_returns_200_silently_when_account_ACTIVE() {
        String phone = uniquePhone();
        seedActiveAccount(phone);

        ResponseEntity<Void> resp =
                postJsonVoid("/api/v1/auth/cancel-deletion/sms-codes", body("phone", phone), uniqueIp());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(smsClient, never()).send(anyString(), anyString(), any());
    }

    @Test
    void sms_codes_returns_200_silently_when_account_FROZEN_grace_expired() {
        String phone = uniquePhone();
        // freezeUntil = 1ms ago — grace already expired
        seedFrozenAccount(phone, Duration.ofMillis(-1));

        ResponseEntity<Void> resp =
                postJsonVoid("/api/v1/auth/cancel-deletion/sms-codes", body("phone", phone), uniqueIp());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(smsClient, never()).send(anyString(), anyString(), any());
    }

    // ── US3: cancel 401 — phone-class branches ────────────────────────────────

    @Test
    void cancel_returns_401_INVALID_CREDENTIALS_when_phone_not_registered() {
        ResponseEntity<String> resp =
                postJson("/api/v1/auth/cancel-deletion", body("phone", uniquePhone(), "code", "123456"), uniqueIp());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void cancel_returns_401_INVALID_CREDENTIALS_when_account_ACTIVE() {
        String phone = uniquePhone();
        seedActiveAccount(phone);

        ResponseEntity<String> resp =
                postJson("/api/v1/auth/cancel-deletion", body("phone", phone, "code", "123456"), uniqueIp());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void cancel_returns_401_INVALID_CREDENTIALS_when_account_FROZEN_grace_expired() {
        String phone = uniquePhone();
        seedFrozenAccount(phone, Duration.ofMillis(-1));

        ResponseEntity<String> resp =
                postJson("/api/v1/auth/cancel-deletion", body("phone", phone, "code", "123456"), uniqueIp());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("INVALID_CREDENTIALS");
    }

    // ── US3: cancel 401 — code branches ───────────────────────────────────────

    @Test
    void cancel_returns_401_when_no_prior_code_sent() {
        String phone = uniquePhone();
        seedFrozenAccount(phone, Duration.ofDays(10));

        ResponseEntity<String> resp =
                postJson("/api/v1/auth/cancel-deletion", body("phone", phone, "code", "123456"), uniqueIp());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void cancel_returns_401_when_wrong_code_submitted() {
        String phone = uniquePhone();
        String ip = uniqueIp();
        seedFrozenAccount(phone, Duration.ofDays(10));

        // send code (real one captured but we submit a wrong one)
        postJsonVoid("/api/v1/auth/cancel-deletion/sms-codes", body("phone", phone), ip);
        verify(smsClient).send(anyString(), anyString(), any()); // sanity: code was sent

        ResponseEntity<String> resp =
                postJson("/api/v1/auth/cancel-deletion", body("phone", phone, "code", "000000"), ip);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void cancel_returns_401_on_second_attempt_when_code_already_used() {
        String phone = uniquePhone();
        String ip = uniqueIp();
        seedFrozenAccount(phone, Duration.ofDays(10));

        postJsonVoid("/api/v1/auth/cancel-deletion/sms-codes", body("phone", phone), ip);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(smsClient).send(anyString(), anyString(), paramsCaptor.capture());
        String code = paramsCaptor.getValue().get("code");

        // First cancel: succeeds
        ResponseEntity<String> first = postJson("/api/v1/auth/cancel-deletion", body("phone", phone, "code", code), ip);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second cancel with the same code: must fail (account is now ACTIVE → phone branch
        // blocks regardless of code state, but the code is also markUsed)
        ResponseEntity<String> second =
                postJson("/api/v1/auth/cancel-deletion", body("phone", phone, "code", code), ip);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(second.getBody()).contains("INVALID_CREDENTIALS");
    }

    // ── Body validation ───────────────────────────────────────────────────────

    @Test
    void sms_codes_returns_400_when_phone_format_invalid_at_web_layer() {
        ResponseEntity<String> resp =
                postJson("/api/v1/auth/cancel-deletion/sms-codes", "{\"phone\":\"not-a-phone\"}", uniqueIp());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void cancel_returns_400_when_code_field_has_invalid_format() {
        ResponseEntity<String> resp = postJson(
                "/api/v1/auth/cancel-deletion",
                "{\"phone\":\"" + uniquePhone() + "\",\"code\":\"abc123\"}",
                uniqueIp());

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

    /**
     * Seed a FROZEN account whose freeze_until = now + {@code graceRemaining}.
     * Use a positive Duration for grace-still-valid; a negative one for grace expired.
     */
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

    private ResponseEntity<Void> postJsonVoid(String path, String body, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
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
