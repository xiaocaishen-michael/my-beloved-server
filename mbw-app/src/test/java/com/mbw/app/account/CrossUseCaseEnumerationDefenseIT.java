package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.UUID;
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
 * SC-005 cross-use-case enumeration defense (T13).
 *
 * <p>Verifies that {@code INVALID_CREDENTIALS} 401 responses across
 * three independent failure paths are byte-identical (status + body
 * code + body shape), preventing an attacker from distinguishing:
 *
 * <ol>
 *   <li>register-by-phone with a phone that's already registered
 *       (caught by {@code uk_account_phone} → DIV → 401)
 *   <li>login-by-phone-sms on an unregistered phone (caught by
 *       {@code findByPhone.empty} → 401)
 *   <li>login-by-phone-sms with a wrong code on a registered phone
 *       (caught by SmsCodeService.verify→false → 401)
 * </ol>
 *
 * <p>The traceId field is allowed to differ (it's per-request); we
 * normalize it to a placeholder before equality comparison.
 *
 * <p>Future use cases (login-by-password, refresh-token, logout-all)
 * extend this test in subsequent phases per analysis.md A3 — once
 * impl PRs land they should add their failure-path response and assert
 * the same byte-identical shape.
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CrossUseCaseEnumerationDefenseIT {

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
    void register_already_vs_login_unregistered_vs_login_invalid_code_byte_identical() {
        doNothing().when(smsClient).send(any(), any(), any());

        // Path 1: register an already-registered phone → DIV → INVALID_CREDENTIALS
        // Pre-seed via repos so /sms-codes per-phone bucket is fresh; the first
        // /sms-codes call hits the Template B branch (registered + REGISTER
        // purpose), which does not generate a code. Then /register-by-phone
        // with any code triggers uk_account_phone DIV.
        String registeredPhone = uniquePhone();
        seedActiveAccount(registeredPhone);
        ResponseEntity<Void> reSms = restTemplate.postForEntity(
                "/api/v1/sms-codes", jsonRequest("{\"phone\":\"" + registeredPhone + "\"}", uniqueIp()), Void.class);
        assertThat(reSms.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> registerAlready = restTemplate.postForEntity(
                "/api/v1/accounts/register-by-phone",
                jsonRequest("{\"phone\":\"" + registeredPhone + "\",\"code\":\"123456\"}", uniqueIp()),
                String.class);

        // Path 2: login with an unregistered phone → 401
        String unregisteredPhone = uniquePhone();
        ResponseEntity<String> loginUnregistered = restTemplate.postForEntity(
                "/api/v1/auth/login-by-phone-sms",
                jsonRequest("{\"phone\":\"" + unregisteredPhone + "\",\"code\":\"123456\"}", uniqueIp()),
                String.class);

        // Path 3: login with wrong code on a registered phone → 401
        String anotherRegistered = uniquePhone();
        seedActiveAccount(anotherRegistered);
        // Request a fresh LOGIN code (per-phone bucket fresh), then submit wrong code
        restTemplate.postForEntity(
                "/api/v1/sms-codes",
                jsonRequest("{\"phone\":\"" + anotherRegistered + "\",\"purpose\":\"LOGIN\"}", uniqueIp()),
                Void.class);
        ResponseEntity<String> loginWrongCode = restTemplate.postForEntity(
                "/api/v1/auth/login-by-phone-sms",
                jsonRequest("{\"phone\":\"" + anotherRegistered + "\",\"code\":\"000000\"}", uniqueIp()),
                String.class);

        // All three must be HTTP 401 + body code INVALID_CREDENTIALS
        assertThat(registerAlready.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(loginUnregistered.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(loginWrongCode.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String n1 = normalize(registerAlready.getBody());
        String n2 = normalize(loginUnregistered.getBody());
        String n3 = normalize(loginWrongCode.getBody());

        assertThat(n1)
                .as("register-already vs login-unregistered byte-identical (after traceId normalize)")
                .isEqualTo(n2);
        assertThat(n2)
                .as("login-unregistered vs login-wrong-code byte-identical (after traceId normalize)")
                .isEqualTo(n3);
    }

    /**
     * Strip per-request fields (traceId, instance URL — auto-filled by
     * RFC 9457 ProblemDetail per request path) so the remaining body
     * shape (type / title / status / detail / code) can be compared
     * byte-for-byte. Both fields are visible to the attacker by virtue
     * of the request itself; the enumeration defense is on the
     * content shape, not the request path.
     */
    private static String normalize(String body) {
        if (body == null) {
            return "";
        }
        return body.replaceAll("\"traceId\"\\s*:\\s*\"[^\"]*\"", "\"traceId\":\"X\"")
                .replaceAll("\"instance\"\\s*:\\s*\"[^\"]*\"", "\"instance\":\"X\"");
    }

    /**
     * Pre-seed an ACTIVE account directly through repos to avoid the
     * per-phone /sms-codes 60s rate-limit interfering with subsequent
     * code requests for the same phone.
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
}
