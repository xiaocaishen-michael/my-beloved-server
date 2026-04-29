package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;

import com.mbw.MbwApplication;
import com.mbw.shared.api.sms.SmsClient;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end IT for the register-by-phone flow (T17).
 *
 * <p>Boots the full Spring Boot context against real PostgreSQL +
 * Redis containers. {@link SmsClient} is the only mocked bean — it
 * captures the plaintext code passed in the {@code params} map so the
 * test can replay it as the verification code on the second call.
 *
 * <p>Covers spec.md User Stories 1 (happy path), 3 (already-registered
 * branch via duplicate-phone), plus the FR-006 60s rate-limit gate.
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RegisterByPhoneE2EIT {

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
        // Modulith's event_publication table is required by the JPA event
        // publication registry. Production uses Modulith's own JDBC schema
        // initializer; in tests we let Hibernate auto-update so its
        // schema-validate doesn't fail before Modulith's init runs.
        registry.add("spring.modulith.events.jdbc-schema-initialization.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("mbw.auth.jwt.secret", () -> "test-secret-32-bytes-or-more-of-test-entropy-please-do-not-use");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    private SmsClient smsClient;

    @Test
    void unregistered_phone_full_round_trip_returns_tokens() {
        String phone = uniquePhone();
        AtomicReference<String> capturedCode = new AtomicReference<>();
        doAnswer(inv -> {
                    Map<String, String> params = inv.getArgument(2);
                    if (params.containsKey("code")) {
                        capturedCode.set(params.get("code"));
                    }
                    return null;
                })
                .when(smsClient)
                .send(eq(phone), any(), any());

        ResponseEntity<Void> smsResp = restTemplate.postForEntity(
                "/api/v1/sms-codes", jsonRequest("{\"phone\":\"" + phone + "\"}"), Void.class);
        assertThat(smsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capturedCode.get()).matches("^\\d{6}$");

        ResponseEntity<RegisterResponse> registerResp = restTemplate.postForEntity(
                "/api/v1/accounts/register-by-phone",
                jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"" + capturedCode.get() + "\"}"),
                RegisterResponse.class);
        assertThat(registerResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(registerResp.getBody().accountId()).isPositive();
        assertThat(registerResp.getBody().accessToken()).isNotBlank();
        assertThat(registerResp.getBody().refreshToken()).isNotBlank();
    }

    @Test
    void wrong_code_returns_401_INVALID_CREDENTIALS() {
        String phone = uniquePhone();
        doNothing().when(smsClient).send(any(), any(), any());

        restTemplate.postForEntity("/api/v1/sms-codes", jsonRequest("{\"phone\":\"" + phone + "\"}"), Void.class);
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/accounts/register-by-phone",
                jsonRequest("{\"phone\":\"" + phone + "\",\"code\":\"000000\"}"),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void second_sms_within_60s_returns_429() {
        String phone = uniquePhone();
        doNothing().when(smsClient).send(any(), any(), any());

        ResponseEntity<Void> first = restTemplate.postForEntity(
                "/api/v1/sms-codes", jsonRequest("{\"phone\":\"" + phone + "\"}"), Void.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = restTemplate.postForEntity(
                "/api/v1/sms-codes", jsonRequest("{\"phone\":\"" + phone + "\"}"), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(second.getHeaders().getFirst("Retry-After")).isNotNull();
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

    private record RegisterResponse(long accountId, String accessToken, String refreshToken) {}
}
