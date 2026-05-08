package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbw.MbwApplication;
import com.mbw.account.api.dto.DeviceType;
import com.mbw.account.api.dto.LoginMethod;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.DeviceId;
import com.mbw.account.domain.model.IpAddress;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.model.RefreshTokenRecordId;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.account.domain.service.RefreshTokenHasher;
import com.mbw.account.domain.service.TokenIssuer;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end IT for device-management T15 — exercises the key
 * scenarios from the spec's five User Stories against the live
 * Spring + Testcontainers stack.
 *
 * <p>Scope is the device-management endpoints + their integration
 * with auth filter, rate-limiter, repository, and rolling event
 * publisher. Per-UseCase logic branches (size clamp, every
 * rate-limit bucket, every input-validation slice) are already
 * covered by the unit + MockMvc tests; this IT picks one
 * representative case per User Story.
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DeviceManagementE2EIT {

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

    private final ObjectMapper json = new ObjectMapper();

    // ── US1: list ─────────────────────────────────────────────────────────────

    @Test
    void US1_list_returns_active_devices_with_isCurrent_flag() throws Exception {
        Seeded session = seedThreeDevicesForOneAccount();
        String accessJwt = tokenIssuer.signAccess(session.accountId, session.currentDeviceId);

        ResponseEntity<String> resp = bearerGet("/api/v1/auth/devices?page=0&size=10", accessJwt);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json.readTree(resp.getBody());
        assertThat(body.get("totalElements").asLong()).isEqualTo(3);
        assertThat(body.get("items")).hasSize(3);

        long currentCount = 0;
        for (JsonNode item : body.get("items")) {
            if (item.get("isCurrent").asBoolean()) {
                currentCount++;
                assertThat(item.get("deviceId").asText()).isEqualTo(session.currentDeviceId.value());
            }
        }
        assertThat(currentCount).as("exactly one isCurrent=true row").isEqualTo(1);
    }

    @Test
    void US1_list_paginates_when_total_exceeds_page_size() throws Exception {
        Seeded session = seedManyDevicesForOneAccount(12);
        String accessJwt = tokenIssuer.signAccess(session.accountId, session.currentDeviceId);

        JsonNode page0 = json.readTree(
                bearerGet("/api/v1/auth/devices?page=0&size=10", accessJwt).getBody());
        JsonNode page1 = json.readTree(
                bearerGet("/api/v1/auth/devices?page=1&size=10", accessJwt).getBody());

        assertThat(page0.get("totalElements").asLong()).isEqualTo(12);
        assertThat(page0.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page0.get("items")).hasSize(10);
        assertThat(page1.get("items")).hasSize(2);
    }

    // ── US2: revoke other device ─────────────────────────────────────────────

    @Test
    void US2_revoke_other_device_marks_row_revoked() {
        Seeded session = seedThreeDevicesForOneAccount();
        String accessJwt = tokenIssuer.signAccess(session.accountId, session.currentDeviceId);
        RefreshTokenRecordId targetId = session.otherRecordIds.get(0);

        ResponseEntity<Void> resp = bearerDelete("/api/v1/auth/devices/" + targetId.value(), accessJwt);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        RefreshTokenRecord reloaded = refreshTokenRepository.findById(targetId).orElseThrow();
        assertThat(reloaded.revokedAt()).isNotNull();
    }

    @Test
    void US2_revoke_returns_404_for_recordId_belonging_to_other_account() {
        Seeded sessionA = seedThreeDevicesForOneAccount();
        Seeded sessionB = seedThreeDevicesForOneAccount();
        String accessJwtA = tokenIssuer.signAccess(sessionA.accountId, sessionA.currentDeviceId);
        // Try to delete one of B's recordIds with A's access token.
        RefreshTokenRecordId crossAccountTarget = sessionB.otherRecordIds.get(0);

        ResponseEntity<String> resp =
                bearerDeleteForBody("/api/v1/auth/devices/" + crossAccountTarget.value(), accessJwtA);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).contains("DEVICE_NOT_FOUND");
        // Anti-enumeration: B's row was not touched.
        RefreshTokenRecord reloaded =
                refreshTokenRepository.findById(crossAccountTarget).orElseThrow();
        assertThat(reloaded.revokedAt()).isNull();
    }

    @Test
    void US2_revoke_returns_404_for_nonexistent_recordId() {
        Seeded session = seedThreeDevicesForOneAccount();
        String accessJwt = tokenIssuer.signAccess(session.accountId, session.currentDeviceId);

        ResponseEntity<String> resp = bearerDeleteForBody("/api/v1/auth/devices/9999999", accessJwt);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).contains("DEVICE_NOT_FOUND");
    }

    // ── US3: self-revoke rejected ────────────────────────────────────────────

    @Test
    void US3_revoke_current_device_returns_409() {
        Seeded session = seedThreeDevicesForOneAccount();
        String accessJwt = tokenIssuer.signAccess(session.accountId, session.currentDeviceId);

        ResponseEntity<String> resp =
                bearerDeleteForBody("/api/v1/auth/devices/" + session.currentRecordId.value(), accessJwt);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).contains("CANNOT_REMOVE_CURRENT_DEVICE");
        // Current device row stays active.
        RefreshTokenRecord reloaded =
                refreshTokenRepository.findById(session.currentRecordId).orElseThrow();
        assertThat(reloaded.revokedAt()).isNull();
    }

    @Test
    @SuppressWarnings("deprecation") // legacy signAccess(AccountId) — emits a token without did
    void US3_token_without_did_claim_is_rejected_401() {
        Seeded session = seedThreeDevicesForOneAccount();
        // Issue a legacy token (no did) — simulates pre-upgrade access tokens.
        String legacyJwt = tokenIssuer.signAccess(session.accountId);

        ResponseEntity<String> list = bearerGetForBody("/api/v1/auth/devices", legacyJwt);
        ResponseEntity<String> revoke = bearerDeleteForBody(
                "/api/v1/auth/devices/" + session.otherRecordIds.get(0).value(), legacyJwt);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(revoke.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── US4: auth failures ────────────────────────────────────────────────────

    @Test
    void US4_no_authorization_header_returns_401() {
        ResponseEntity<String> list = restTemplate.exchange(
                "/api/v1/auth/devices", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void US4_malformed_bearer_returns_401() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer not-a-jwt");
        ResponseEntity<String> list =
                restTemplate.exchange("/api/v1/auth/devices", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<String> bearerGet(String path, String accessJwt) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearerHeaders(accessJwt)), String.class);
    }

    private ResponseEntity<String> bearerGetForBody(String path, String accessJwt) {
        return bearerGet(path, accessJwt);
    }

    private ResponseEntity<Void> bearerDelete(String path, String accessJwt) {
        return restTemplate.exchange(path, HttpMethod.DELETE, new HttpEntity<>(bearerHeaders(accessJwt)), Void.class);
    }

    private ResponseEntity<String> bearerDeleteForBody(String path, String accessJwt) {
        return restTemplate.exchange(path, HttpMethod.DELETE, new HttpEntity<>(bearerHeaders(accessJwt)), String.class);
    }

    private static HttpHeaders bearerHeaders(String accessJwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessJwt);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Seeded seedThreeDevicesForOneAccount() {
        return seedManyDevicesForOneAccount(3);
    }

    private Seeded seedManyDevicesForOneAccount(int count) {
        return transactionTemplate.execute(status -> {
            String phone = uniquePhone();
            Account account = new Account(new PhoneNumber(phone), Instant.now());
            AccountStateMachine.activate(account, Instant.now());
            Account saved = accountRepository.save(account);
            credentialRepository.save(new PhoneCredential(saved.id(), new PhoneNumber(phone), Instant.now()));

            DeviceId currentDevice = randomDeviceId();
            Instant now = Instant.now();
            Instant expires = now.plus(Duration.ofDays(30));

            RefreshTokenRecord currentRow = refreshTokenRepository.save(RefreshTokenRecord.createActive(
                    RefreshTokenHasher.hash(tokenIssuer.signRefresh()),
                    saved.id(),
                    currentDevice,
                    /* deviceName */ null,
                    DeviceType.PHONE,
                    IpAddress.ofNullable("203.0.113.10"),
                    LoginMethod.PHONE_SMS,
                    expires,
                    now));

            java.util.List<RefreshTokenRecordId> others = new java.util.ArrayList<>();
            for (int i = 1; i < count; i++) {
                RefreshTokenRecord row = refreshTokenRepository.save(RefreshTokenRecord.createActive(
                        RefreshTokenHasher.hash(tokenIssuer.signRefresh()),
                        saved.id(),
                        randomDeviceId(),
                        /* deviceName */ null,
                        DeviceType.UNKNOWN,
                        IpAddress.ofNullable("203.0.113." + (20 + i)),
                        LoginMethod.PHONE_SMS,
                        expires,
                        now.plusSeconds(i)));
                others.add(row.id());
            }

            return new Seeded(saved.id(), currentDevice, currentRow.id(), others);
        });
    }

    private static String uniquePhone() {
        long suffix = Math.abs(UUID.randomUUID().getMostSignificantBits()) % 10_000_000_000L;
        return "+8613" + String.format("%010d", suffix).substring(1);
    }

    private static DeviceId randomDeviceId() {
        return new DeviceId(UUID.randomUUID().toString());
    }

    private record Seeded(
            AccountId accountId,
            DeviceId currentDeviceId,
            RefreshTokenRecordId currentRecordId,
            java.util.List<RefreshTokenRecordId> otherRecordIds) {}
}
