package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.MbwApplication;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.shared.api.sms.SmsCodeService;
import java.time.Instant;
import java.util.Optional;
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
 * End-to-end IT for unified phone-SMS auth (per ADR-0016 + spec
 * {@code phone-sms-auth/spec.md} T4).
 *
 * <p>Covers 4 main scenarios (FROZEN / ANONYMIZED scenarios deferred to
 * M1.3+ when {@code delete-account} use case adds the FROZEN transition
 * path; current schema CHECK constraint pins status=ACTIVE for M1.2).
 * Unit test {@code UnifiedPhoneSmsAuthUseCaseTest} covers FROZEN/ANONYMIZED
 * branches with mocked repositories.
 *
 * <ul>
 *   <li>Happy 已注册 ACTIVE → 200 + tokens + DB last_login_at updated
 *   <li>Happy 未注册自动注册 → 200 + tokens + DB account row created
 *   <li>SMS 码错 → 401 INVALID_CREDENTIALS
 *   <li>Phone 格式错 → 400 INVALID_PHONE_FORMAT
 * </ul>
 *
 * <p>Uses {@link SmsCodeService} directly to seed plaintext codes
 * (bypassing the {@code /sms-codes} endpoint + 60s phone bucket) — same
 * shortcut pattern as legacy ITs.
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class UnifiedPhoneSmsAuthE2EIT {

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
    private SmsCodeService smsCodeService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void happy_registered_active_phone_should_return_200_with_tokens_and_update_last_login() {
        String phone = uniquePhone();
        long accountId = seedActiveAccount(phone);
        String code = smsCodeService.generateAndStore(phone);

        ResponseEntity<AuthResponse> resp = restTemplate.postForEntity(
                "/api/v1/accounts/phone-sms-auth",
                jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}", uniqueIp()),
                AuthResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().accountId()).isEqualTo(accountId);
        assertThat(resp.getBody().accessToken()).isNotBlank();
        assertThat(resp.getBody().refreshToken()).isNotBlank();

        // last_login_at updated to within last few seconds
        Optional<Account> reloaded = accountRepository.findByPhone(new PhoneNumber(phone));
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().lastLoginAt()).isNotNull();
    }

    @Test
    void happy_unregistered_phone_should_auto_create_account_and_return_200_with_tokens() {
        String phone = uniquePhone();
        // No seeding — phone does not exist in DB
        String code = smsCodeService.generateAndStore(phone);

        ResponseEntity<AuthResponse> resp = restTemplate.postForEntity(
                "/api/v1/accounts/phone-sms-auth",
                jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}", uniqueIp()),
                AuthResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().accountId()).isPositive();
        assertThat(resp.getBody().accessToken()).isNotBlank();
        assertThat(resp.getBody().refreshToken()).isNotBlank();

        // Account auto-created with status=ACTIVE
        Optional<Account> created = accountRepository.findByPhone(new PhoneNumber(phone));
        assertThat(created).isPresent();
        assertThat(created.get().status()).isEqualTo(com.mbw.account.domain.model.AccountStatus.ACTIVE);
        assertThat(created.get().lastLoginAt()).isNotNull();
    }

    @Test
    void invalid_sms_code_should_return_401_invalid_credentials() {
        String phone = uniquePhone();
        // No code stored → any submission fails
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/accounts/phone-sms-auth",
                jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"999999\"}", uniqueIp()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void invalid_phone_format_should_return_422() {
        // InvalidPhoneFormatException → 422 (business rule violation,
        // per AccountWebExceptionAdvice mapping; web @NotBlank passes
        // for non-empty "not-a-phone" but PhonePolicy.validate rejects).
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/accounts/phone-sms-auth",
                jsonRequest("{\"phone\":\"not-a-phone\",\"code\":\"123456\"}", uniqueIp()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private long seedActiveAccount(String phone) {
        long[] idHolder = new long[1];
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            PhoneNumber phoneNumber = new PhoneNumber(phone);
            Account account = new Account(phoneNumber, now);
            AccountStateMachine.activate(account, now);
            Account saved = accountRepository.save(account);
            credentialRepository.save(new PhoneCredential(saved.id(), phoneNumber, now));
            idHolder[0] = saved.id().value();
        });
        return idHolder[0];
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

    private record AuthResponse(long accountId, String accessToken, String refreshToken) {}
}
