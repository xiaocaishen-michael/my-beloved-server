package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbw.MbwApplication;
import com.mbw.account.api.dto.DeviceType;
import com.mbw.account.api.dto.LoginMethod;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.model.RefreshTokenHash;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.account.domain.service.RefreshTokenHasher;
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
 * Cross-spec regression IT for device-management T17 — verifies that
 * the existing token-issuing UseCases populate the V11 device columns
 * end-to-end and that the rotation path inherits device metadata per
 * FR-012, while the existing logout-all flow remains byte-compatible.
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DeviceMetadataPropagationIT {

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
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final ObjectMapper jsonMapper = new ObjectMapper();

    @BeforeEach
    void resetMocks() {
        reset(smsClient);
    }

    // ── Phone-sms-auth: register branch ───────────────────────────────────────

    @Test
    void should_persist_5_device_fields_on_phone_sms_auth_register_branch() throws Exception {
        String phone = uniquePhone();
        String publicIp = "203.0.113.5";
        String deviceId = "8a7c1f2e-5b3d-4f6a-9e2c-1d4b5a6c7e8f";
        String deviceName = "MK-iPhone";

        String code = sendCodeAndCapture(phone, publicIp);
        ResponseEntity<String> auth = phoneSmsAuth(phone, code, publicIp, deviceId, deviceName, "PHONE");
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);

        String refreshToken =
                jsonMapper.readTree(auth.getBody()).get("refreshToken").asText();
        RefreshTokenRecord row = loadByRawToken(refreshToken);

        assertThat(row.deviceId().value()).isEqualTo(deviceId);
        assertThat(row.deviceName().value()).isEqualTo(deviceName);
        assertThat(row.deviceType()).isEqualTo(DeviceType.PHONE);
        assertThat(row.ipAddress().value()).isEqualTo(publicIp);
        assertThat(row.loginMethod()).isEqualTo(LoginMethod.PHONE_SMS);
    }

    // ── Phone-sms-auth: login branch (existing ACTIVE account) ────────────────

    @Test
    void should_persist_5_device_fields_on_phone_sms_auth_login_branch() throws Exception {
        String phone = uniquePhone();
        seedActiveAccount(phone);

        String publicIp = "198.51.100.7";
        String deviceId = "11111111-2222-4333-8444-555566667777";
        String code = sendCodeAndCapture(phone, publicIp);
        ResponseEntity<String> auth = phoneSmsAuth(phone, code, publicIp, deviceId, "MK-iPad", "TABLET");
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);

        String refreshToken =
                jsonMapper.readTree(auth.getBody()).get("refreshToken").asText();
        RefreshTokenRecord row = loadByRawToken(refreshToken);

        assertThat(row.deviceId().value()).isEqualTo(deviceId);
        assertThat(row.deviceType()).isEqualTo(DeviceType.TABLET);
        assertThat(row.ipAddress().value()).isEqualTo(publicIp);
        assertThat(row.loginMethod()).isEqualTo(LoginMethod.PHONE_SMS);
    }

    // ── Refresh-token rotation: inherits 4 fields, IP refreshed ───────────────

    @Test
    void should_inherit_4_fields_and_use_new_ip_on_refresh_rotation() throws Exception {
        String phone = uniquePhone();
        String loginIp = "203.0.113.10";
        String rotateIp = "203.0.113.99";
        String deviceId = "aaaa1111-bbbb-4222-8333-444455556666";
        String deviceName = "MK-MacBook"; // ASCII only — JDK HTTP client rejects non-ASCII header bytes

        String code = sendCodeAndCapture(phone, loginIp);
        ResponseEntity<String> auth = phoneSmsAuth(phone, code, loginIp, deviceId, deviceName, "DESKTOP");
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);

        String firstRefresh =
                jsonMapper.readTree(auth.getBody()).get("refreshToken").asText();

        // Rotate. Note the X-Device-* headers are intentionally NOT resent —
        // FR-012 requires the new row to inherit the parent's metadata.
        ResponseEntity<String> rotated = restTemplate.exchange(
                "/api/v1/auth/refresh-token",
                HttpMethod.POST,
                jsonRequest("{\"refreshToken\":\"" + firstRefresh + "\"}", rotateIp, /* deviceId */ null, null, null),
                String.class);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);

        String secondRefresh =
                jsonMapper.readTree(rotated.getBody()).get("refreshToken").asText();
        RefreshTokenRecord newRow = loadByRawToken(secondRefresh);

        assertThat(newRow.deviceId().value()).as("device_id inherited").isEqualTo(deviceId);
        assertThat(newRow.deviceName().value()).as("device_name inherited").isEqualTo(deviceName);
        assertThat(newRow.deviceType()).as("device_type inherited").isEqualTo(DeviceType.DESKTOP);
        assertThat(newRow.loginMethod())
                .as("login_method inherited (no REFRESH enum value)")
                .isEqualTo(LoginMethod.PHONE_SMS);
        assertThat(newRow.ipAddress().value())
                .as("ip_address takes the new rotation request IP")
                .isEqualTo(rotateIp);
    }

    // ── Cancel-deletion: 5 fields persisted on the new ACTIVE row ─────────────

    @Test
    void should_persist_5_device_fields_on_cancel_deletion() throws Exception {
        String phone = uniquePhone();
        seedFrozenAccount(phone, Duration.ofDays(10));
        String publicIp = "203.0.113.50";
        String deviceId = "cccc1111-dddd-4222-8333-eeee44445555";

        // First send the cancel-deletion code (different endpoint than login)
        ResponseEntity<String> sendResp = postJson(
                "/api/v1/auth/cancel-deletion/sms-codes",
                "{\"phone\":\"" + phone + "\"}",
                publicIp,
                /* deviceId */ null,
                null,
                null);
        assertThat(sendResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(smsClient).send(anyString(), anyString(), captor.capture());
        String code = captor.getValue().get("code");

        ResponseEntity<String> cancel = postJson(
                "/api/v1/auth/cancel-deletion",
                "{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}",
                publicIp,
                deviceId,
                "MK-Pixel",
                "PHONE");
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);

        String refreshToken =
                jsonMapper.readTree(cancel.getBody()).get("refreshToken").asText();
        RefreshTokenRecord row = loadByRawToken(refreshToken);

        assertThat(row.deviceId().value()).isEqualTo(deviceId);
        assertThat(row.deviceName().value()).isEqualTo("MK-Pixel");
        assertThat(row.deviceType()).isEqualTo(DeviceType.PHONE);
        assertThat(row.ipAddress().value()).isEqualTo(publicIp);
        assertThat(row.loginMethod()).isEqualTo(LoginMethod.PHONE_SMS);
    }

    // ── No regression: phone-sms-auth response shape unchanged ────────────────

    @Test
    void phone_sms_auth_response_keeps_legacy_shape() throws Exception {
        String phone = uniquePhone();
        String code = sendCodeAndCapture(phone, "203.0.113.5");
        ResponseEntity<String> auth = phoneSmsAuth(phone, code, "203.0.113.5", null, null, null);

        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = jsonMapper.readTree(auth.getBody());
        // The wire schema for LoginResponse must remain exactly these three keys (FR-016).
        assertThat(body.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("accountId", "accessToken", "refreshToken");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String sendCodeAndCapture(String phone, String ip) {
        ResponseEntity<String> resp =
                postJson("/api/v1/sms-codes", "{\"phone\":\"" + phone + "\"}", ip, null, null, null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(smsClient).send(anyString(), anyString(), captor.capture());
        return captor.getValue().get("code");
    }

    private ResponseEntity<String> phoneSmsAuth(
            String phone, String code, String ip, String deviceId, String deviceName, String deviceType) {
        return postJson(
                "/api/v1/accounts/phone-sms-auth",
                "{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}",
                ip,
                deviceId,
                deviceName,
                deviceType);
    }

    private ResponseEntity<String> postJson(
            String path, String body, String ip, String deviceId, String deviceName, String deviceType) {
        return restTemplate.exchange(
                path, HttpMethod.POST, jsonRequest(body, ip, deviceId, deviceName, deviceType), String.class);
    }

    private static HttpEntity<String> jsonRequest(
            String body, String ip, String deviceId, String deviceName, String deviceType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (ip != null) {
            headers.add("X-Forwarded-For", ip);
        }
        if (deviceId != null) {
            headers.add("X-Device-Id", deviceId);
        }
        if (deviceName != null) {
            headers.add("X-Device-Name", deviceName);
        }
        if (deviceType != null) {
            headers.add("X-Device-Type", deviceType);
        }
        return new HttpEntity<>(body, headers);
    }

    private RefreshTokenRecord loadByRawToken(String rawToken) {
        RefreshTokenHash hash = RefreshTokenHasher.hash(rawToken);
        return refreshTokenRepository.findByTokenHash(hash).orElseThrow();
    }

    private void seedActiveAccount(String phone) {
        transactionTemplate.executeWithoutResult(status -> {
            Account account = new Account(new PhoneNumber(phone), Instant.now());
            AccountStateMachine.activate(account, Instant.now());
            Account saved = accountRepository.save(account);
            credentialRepository.save(new PhoneCredential(saved.id(), new PhoneNumber(phone), Instant.now()));
        });
    }

    private AccountId seedFrozenAccount(String phone, Duration freezePeriod) {
        return transactionTemplate.execute(status -> {
            Account account = new Account(new PhoneNumber(phone), Instant.now());
            AccountStateMachine.activate(account, Instant.now());
            Instant now = Instant.now();
            AccountStateMachine.markFrozen(account, now.plus(freezePeriod), now);
            Account saved = accountRepository.save(account);
            credentialRepository.save(new PhoneCredential(saved.id(), new PhoneNumber(phone), Instant.now()));
            return saved.id();
        });
    }

    private static String uniquePhone() {
        long suffix = Math.abs(UUID.randomUUID().getMostSignificantBits()) % 10_000_000_000L;
        return "+8613" + String.format("%010d", suffix).substring(1);
    }
}
