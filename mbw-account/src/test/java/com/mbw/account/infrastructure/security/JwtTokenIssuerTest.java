package com.mbw.account.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.account.domain.model.AccountId;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
}
