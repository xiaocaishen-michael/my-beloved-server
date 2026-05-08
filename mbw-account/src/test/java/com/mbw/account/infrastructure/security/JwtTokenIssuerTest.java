package com.mbw.account.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.DeviceId;
import com.mbw.account.domain.service.AuthenticatedTokenClaims;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class JwtTokenIssuerTest {

    private static final String SECRET = "test-secret-with-at-least-32-bytes-of-entropy-please";
    private static final Instant FIXED_NOW = Instant.parse("2026-04-29T01:00:00Z");

    private final JwtProperties props = new JwtProperties(SECRET);
    private final JwtTokenIssuer issuer =
            new JwtTokenIssuer(props, Clock.fixed(FIXED_NOW, ZoneOffset.UTC), new SecureRandom());

    @Test
    void signAccess_should_produce_a_valid_HS256_JWT() throws Exception {
        String token = issuer.signAccess(new AccountId(42L));

        SignedJWT parsed = SignedJWT.parse(token);
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(parsed.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8))))
                .isTrue();
    }

    @Test
    void signAccess_should_carry_accountId_as_subject() throws Exception {
        String token = issuer.signAccess(new AccountId(42L));

        SignedJWT parsed = SignedJWT.parse(token);
        assertThat(parsed.getJWTClaimsSet().getSubject()).isEqualTo("42");
    }

    @Test
    void signAccess_should_set_exp_to_15_minutes_after_clock() throws Exception {
        String token = issuer.signAccess(new AccountId(42L));

        SignedJWT parsed = SignedJWT.parse(token);
        Instant issued = parsed.getJWTClaimsSet().getIssueTime().toInstant();
        Instant expires = parsed.getJWTClaimsSet().getExpirationTime().toInstant();

        assertThat(issued).isEqualTo(FIXED_NOW);
        assertThat(expires).isEqualTo(FIXED_NOW.plus(JwtTokenIssuer.ACCESS_TTL));
    }

    @Test
    void signRefresh_should_be_non_empty_url_safe_base64() {
        String token = issuer.signRefresh();

        assertThat(token).isNotEmpty();
        assertThat(token).matches("^[A-Za-z0-9_-]+$"); // URL-safe base64 without padding
    }

    @Test
    void signRefresh_should_decode_to_32_bytes_of_random() {
        String token = issuer.signRefresh();

        byte[] decoded = java.util.Base64.getUrlDecoder().decode(token);
        assertThat(decoded).hasSize(32);
    }

    @Test
    void signRefresh_should_be_unique_across_calls() {
        Set<String> tokens = IntStream.range(0, 100)
                .mapToObj(i -> issuer.signRefresh())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(tokens).as("100 random refreshes are all distinct").hasSize(100);
    }

    // ----- Device-management spec FR-008 / FR-006 — did claim + device-aware verify -----

    private static final DeviceId DEVICE_ID = new DeviceId("8a7c1f2e-5b3d-4f6a-9e2c-1d4b5a6c7e8f");

    @Test
    void signAccess_with_device_should_include_did_claim() throws Exception {
        String token = issuer.signAccess(new AccountId(42L), DEVICE_ID);

        SignedJWT parsed = SignedJWT.parse(token);
        assertThat(parsed.getJWTClaimsSet().getStringClaim("did")).isEqualTo(DEVICE_ID.value());
    }

    @Test
    void signAccess_with_device_should_keep_subject_iat_exp_unchanged() throws Exception {
        String token = issuer.signAccess(new AccountId(42L), DEVICE_ID);

        SignedJWT parsed = SignedJWT.parse(token);
        assertThat(parsed.getJWTClaimsSet().getSubject()).isEqualTo("42");
        assertThat(parsed.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(FIXED_NOW);
        assertThat(parsed.getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(FIXED_NOW.plus(JwtTokenIssuer.ACCESS_TTL));
    }

    @Test
    void signAccess_with_device_should_throw_when_deviceId_null() {
        assertThatThrownBy(() -> issuer.signAccess(new AccountId(42L), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("deviceId");
    }

    @Test
    void verifyAccessWithDevice_should_return_both_claims_when_token_valid() {
        String token = issuer.signAccess(new AccountId(42L), DEVICE_ID);

        Optional<AuthenticatedTokenClaims> claims = issuer.verifyAccessWithDevice(token);

        assertThat(claims).isPresent();
        assertThat(claims.get().accountId()).isEqualTo(new AccountId(42L));
        assertThat(claims.get().deviceId()).isEqualTo(DEVICE_ID);
    }

    @Test
    void verifyAccessWithDevice_should_return_empty_when_did_claim_missing_per_FR_006() {
        // Old-format token signed without did — FR-006 requires reject.
        String legacyToken = issuer.signAccess(new AccountId(42L));

        Optional<AuthenticatedTokenClaims> claims = issuer.verifyAccessWithDevice(legacyToken);

        assertThat(claims).isEmpty();
    }

    @Test
    void verifyAccessWithDevice_should_return_empty_when_token_expired() {
        String token = issuer.signAccess(new AccountId(42L), DEVICE_ID);

        // Issuer clock fixed at FIXED_NOW; build verifier clock past expiry.
        JwtTokenIssuer pastExpiry = new JwtTokenIssuer(
                props,
                Clock.fixed(FIXED_NOW.plus(JwtTokenIssuer.ACCESS_TTL).plus(Duration.ofSeconds(1)), ZoneOffset.UTC),
                new SecureRandom());

        assertThat(pastExpiry.verifyAccessWithDevice(token)).isEmpty();
    }

    @Test
    void verifyAccessWithDevice_should_return_empty_when_signature_invalid() {
        String token = issuer.signAccess(new AccountId(42L), DEVICE_ID);
        // Tamper the last char of the signature segment to break the MAC.
        String tampered =
                token.substring(0, token.length() - 1) + (token.charAt(token.length() - 1) == 'a' ? 'b' : 'a');

        assertThat(issuer.verifyAccessWithDevice(tampered)).isEmpty();
    }
}
