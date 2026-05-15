package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.MbwApplication;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.util.Date;
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
 * Anti-enumeration uniformness IT for {@code GET /api/v1/accounts/me}
 * (per specs/account/profile/spec.md T8 / SC-005).
 *
 * <p>Asserts that the four "non-200" auth-failure paths produce
 * byte-equal 401 ProblemDetail responses:
 *
 * <ol>
 *   <li>no Authorization header
 *   <li>Bearer token with a garbled signature
 *   <li>Bearer token whose {@code exp} is in the past
 *   <li>Bearer token signed for a non-existent account id
 * </ol>
 *
 * <p>Path 4 substitutes for the spec's "FROZEN/ANONYMIZED account" arm
 * because the V1 schema {@code status} CHECK constraint pins valid rows
 * to {@code ACTIVE} in M1.2 (per {@code UnifiedPhoneSmsAuthE2EIT} note).
 * The unit test {@code GetAccountProfileUseCaseTest} covers the
 * AccountInactiveException → 401 mapping with mocked repositories until
 * the M1.3+ delete-account use case lands the FROZEN transition.
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class JwtAuthFailureUniformnessIT {

    private static final String JWT_SECRET = "test-secret-32-bytes-or-more-of-test-entropy-please-do-not-use";

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
        registry.add("mbw.auth.jwt.secret", () -> JWT_SECRET);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void four_auth_failure_paths_should_return_byte_equal_401_problem_detail() throws Exception {
        ResponseEntity<String> noHeader = call(null);
        ResponseEntity<String> garbled = call("not.a.valid.jwt");
        ResponseEntity<String> expired = call(signedAccessToken(/* accountId= */ 999_999L, /* expiredEpochSec= */ 1L));
        ResponseEntity<String> unknownAccount =
                call(signedAccessToken(/* accountId= */ 999_999L, futureEpochSec(60 * 5)));

        // status code uniform
        assertThat(noHeader.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(garbled.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownAccount.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // content-type uniform (RFC 9457 application/problem+json)
        assertThat(noHeader.getHeaders().getContentType())
                .isEqualTo(garbled.getHeaders().getContentType());
        assertThat(noHeader.getHeaders().getContentType())
                .isEqualTo(expired.getHeaders().getContentType());
        assertThat(noHeader.getHeaders().getContentType())
                .isEqualTo(unknownAccount.getHeaders().getContentType());

        // body byte-equal (no per-request fields like timestamp / traceId
        // are emitted by AccountWebExceptionAdvice.onAuthFailure)
        String reference = noHeader.getBody();
        assertThat(reference).isNotBlank();
        assertThat(reference).contains("\"code\":\"AUTH_FAILED\"");
        assertThat(garbled.getBody()).isEqualTo(reference);
        assertThat(expired.getBody()).isEqualTo(reference);
        assertThat(unknownAccount.getBody()).isEqualTo(reference);
    }

    private ResponseEntity<String> call(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return restTemplate.exchange("/api/v1/accounts/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /**
     * Hand-roll an HS256 access token using the same secret the runtime
     * is configured with. Caller picks {@code exp}; an instant in the
     * past gives an expired token, an instant in the future gives a
     * valid signature against a possibly-unknown {@code sub}.
     */
    private static String signedAccessToken(long accountId, long expEpochSec) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(accountId))
                .issueTime(new Date(0L))
                .expirationTime(new Date(expEpochSec * 1000L))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static long futureEpochSec(long secondsFromNow) {
        return java.time.Instant.now().plusSeconds(secondsFromNow).getEpochSecond();
    }
}
