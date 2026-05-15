package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.MbwApplication;
import com.mbw.shared.api.sms.SmsCodeService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 * End-to-end IT for the account-profile use case (per
 * {@code specs/account/profile/spec.md} T7).
 *
 * <p>Bootstraps each scenario by exercising the production
 * phoneSmsAuth flow to obtain a real Bearer JWT, then drives GET / PATCH
 * {@code /api/v1/accounts/me} with that token. Verifies happy paths
 * (User Stories 1-3), authentication boundary conditions (US 1.3 / 2.4),
 * and DisplayName validation (SC-006 representative cases). Rate-limit
 * exhaustion is unit-test territory; running 60+ HTTP round-trips per
 * scenario in this IT was cut for runtime / Bucket4j-Redis flakiness.
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AccountProfileE2EIT {

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
    private SmsCodeService smsCodeService;

    @Test
    void newly_authenticated_user_GET_me_should_return_200_with_phone_and_null_displayName() {
        AuthSetup setup = registerAndLogin();

        ResponseEntity<ProfileResponse> resp = restTemplate.exchange(
                "/api/v1/accounts/me", HttpMethod.GET, bearerEntity(setup.token(), null), ProfileResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().accountId()).isPositive();
        assertThat(resp.getBody().phone()).isEqualTo(setup.phone());
        assertThat(resp.getBody().displayName()).isNull();
        assertThat(resp.getBody().status()).isEqualTo("ACTIVE");
        assertThat(resp.getBody().createdAt()).isNotBlank();
    }

    @Test
    void PATCH_me_with_valid_displayName_should_return_200_and_persist() {
        AuthSetup setup = registerAndLogin();

        ResponseEntity<ProfileResponse> patchResp = restTemplate.exchange(
                "/api/v1/accounts/me",
                HttpMethod.PATCH,
                bearerEntity(setup.token(), "{\"displayName\":\"Alice\"}"),
                ProfileResponse.class);

        assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patchResp.getBody()).isNotNull();
        assertThat(patchResp.getBody().phone()).isEqualTo(setup.phone());
        assertThat(patchResp.getBody().displayName()).isEqualTo("Alice");

        // Subsequent GET must return the persisted value.
        ResponseEntity<ProfileResponse> getResp = restTemplate.exchange(
                "/api/v1/accounts/me", HttpMethod.GET, bearerEntity(setup.token(), null), ProfileResponse.class);
        assertThat(getResp.getBody()).isNotNull();
        assertThat(getResp.getBody().phone()).isEqualTo(setup.phone());
        assertThat(getResp.getBody().displayName()).isEqualTo("Alice");
    }

    @Test
    void PATCH_me_with_same_displayName_twice_should_be_idempotent() {
        AuthSetup setup = registerAndLogin();

        restTemplate.exchange(
                "/api/v1/accounts/me",
                HttpMethod.PATCH,
                bearerEntity(setup.token(), "{\"displayName\":\"Alice\"}"),
                ProfileResponse.class);
        ResponseEntity<ProfileResponse> second = restTemplate.exchange(
                "/api/v1/accounts/me",
                HttpMethod.PATCH,
                bearerEntity(setup.token(), "{\"displayName\":\"Alice\"}"),
                ProfileResponse.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().phone()).isEqualTo(setup.phone());
        assertThat(second.getBody().displayName()).isEqualTo("Alice");
    }

    @Test
    void GET_me_without_authorization_header_should_return_401_AUTH_FAILED() {
        ResponseEntity<String> resp =
                restTemplate.exchange("/api/v1/accounts/me", HttpMethod.GET, bearerEntity(null, null), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("AUTH_FAILED");
    }

    @Test
    void GET_me_with_garbled_token_should_return_401_AUTH_FAILED() {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/accounts/me", HttpMethod.GET, bearerEntity("totally.garbled.jwt", null), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("AUTH_FAILED");
    }

    @Test
    void PATCH_me_without_authorization_header_should_return_401() {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/accounts/me",
                HttpMethod.PATCH,
                bearerEntity(null, "{\"displayName\":\"Alice\"}"),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("AUTH_FAILED");
    }

    @Test
    void PATCH_me_with_blank_displayName_should_return_400() {
        AuthSetup setup = registerAndLogin();

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/accounts/me",
                HttpMethod.PATCH,
                bearerEntity(setup.token(), "{\"displayName\":\"\"}"),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void PATCH_me_with_above_32_codepoint_displayName_should_return_400() {
        AuthSetup setup = registerAndLogin();
        String thirtyThree = "a".repeat(33);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/accounts/me",
                HttpMethod.PATCH,
                bearerEntity(setup.token(), "{\"displayName\":\"" + thirtyThree + "\"}"),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private AuthSetup registerAndLogin() {
        String phone = uniquePhone();
        String code = smsCodeService.generateAndStore(phone);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", uniqueIp());
        ResponseEntity<AuthResponse> auth = restTemplate.postForEntity(
                "/api/v1/accounts/phone-sms-auth",
                new HttpEntity<>("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}", headers),
                AuthResponse.class);

        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(auth.getBody()).isNotNull();
        return new AuthSetup(auth.getBody().accessToken(), phone);
    }

    private static HttpEntity<String> bearerEntity(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
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

    private record AuthSetup(String token, String phone) {}

    private record ProfileResponse(Long accountId, String phone, String displayName, String status, String createdAt) {}
}
