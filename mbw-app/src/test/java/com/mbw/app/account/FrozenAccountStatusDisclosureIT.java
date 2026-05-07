package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.MbwApplication;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.shared.api.sms.SmsCodeService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * End-to-end disclosure IT for spec D
 * {@code expose-frozen-account-status} (SC-001).
 *
 * <p>FROZEN-account login attempt must return HTTP 403 +
 * {@code application/problem+json} body containing both
 * {@code ACCOUNT_IN_FREEZE_PERIOD} code and an extended
 * {@code freezeUntil} ISO 8601 UTC field, so the spec C
 * {@code delete-account-cancel-deletion-ui} client can render
 * the cancel-deletion intercept modal.
 *
 * <p>Complements {@code SingleEndpointEnumerationDefenseIT}, which
 * deliberately excludes the FROZEN branch (per spec D FR-002 explicit
 * disclosure supersedes anti-enumeration for that path).
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>HTTP 403 + Content-Type {@code application/problem+json}
 *   <li>Body {@code code = ACCOUNT_IN_FREEZE_PERIOD} +
 *       {@code freezeUntil} matches seeded value (microsecond precision
 *       per meta MEMORY {@code feedback_pg_timestamptz_truncate_micros})
 *   <li>DB Account state unchanged (status / freezeUntil / lastLoginAt)
 *       — implicitly proves persistLogin did not run, so no new
 *       refresh-token row was written either
 *   <li>100-request loop maintains consistent disclosure (no race / flake)
 * </ul>
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class FrozenAccountStatusDisclosureIT {

    private static final int LOOP_ITERATIONS = 100;

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
    void should_return_403_account_in_freeze_period_when_frozen_account_logs_in() {
        String phone = uniquePhone();
        Instant freezeUntil = Instant.now().plus(14, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        Account frozen = seedFrozenAccount(phone, freezeUntil);
        Instant lastLoginBefore = frozen.lastLoginAt();

        String code = smsCodeService.generateAndStore(phone);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/accounts/phone-sms-auth",
                jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("\"code\":\"ACCOUNT_IN_FREEZE_PERIOD\"");
        assertThat(body).contains("\"title\":\"Account in freeze period\"");
        assertThat(body).contains("\"freezeUntil\":\"" + freezeUntil.toString() + "\"");
        assertThat(body).contains("\"status\":403");
        assertThat(body).contains("freeze period");

        Account after = accountRepository.findById(frozen.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(AccountStatus.FROZEN);
        assertThat(after.freezeUntil()).isEqualTo(freezeUntil);
        assertThat(after.lastLoginAt()).isEqualTo(lastLoginBefore);
    }

    @Test
    void should_disclose_consistently_across_100_requests() {
        String phone = uniquePhone();
        Instant freezeUntil = Instant.now().plus(14, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        seedFrozenAccount(phone, freezeUntil);

        for (int i = 0; i < LOOP_ITERATIONS; i++) {
            String code = smsCodeService.generateAndStore(phone);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/api/v1/accounts/phone-sms-auth",
                    jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"),
                    String.class);

            assertThat(response.getStatusCode())
                    .as("iteration %d should return 403", i)
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody())
                    .as("iteration %d body should contain ACCOUNT_IN_FREEZE_PERIOD", i)
                    .contains("ACCOUNT_IN_FREEZE_PERIOD");
        }
    }

    private Account seedFrozenAccount(String phone, Instant freezeUntil) {
        return transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            PhoneNumber phoneNumber = new PhoneNumber(phone);
            Account active = new Account(phoneNumber, now);
            AccountStateMachine.activate(active, now);
            Account saved = accountRepository.save(active);
            credentialRepository.save(new PhoneCredential(saved.id(), phoneNumber, now));
            AccountStateMachine.markFrozen(saved, freezeUntil, now);
            return accountRepository.save(saved);
        });
    }

    private static HttpEntity<String> jsonRequest(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static String uniquePhone() {
        long suffix = Math.abs(UUID.randomUUID().getMostSignificantBits()) % 10_000_000_000L;
        return "+8613" + String.format("%010d", suffix).substring(1);
    }
}
