package com.mbw.account.infrastructure.security;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.service.TokenIssuer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Nimbus JOSE-backed implementation of {@link TokenIssuer} (FR-008).
 *
 * <p>Access tokens use HMAC SHA-256 with claims {@code sub=accountId},
 * {@code iat}, and {@code exp = iat + 15min}. Refresh tokens are 256
 * random bits from {@link SecureRandom} encoded as URL-safe base64
 * without padding.
 *
 * <p>Clock-injectable so tests can pin time and assert exp boundaries
 * without flakiness.
 */
@Component
public class JwtTokenIssuer implements TokenIssuer {

    static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final JWSSigner signer;
    private final JWSVerifier verifier;
    private final SecureRandom random;
    private final Clock clock;

    @Autowired
    public JwtTokenIssuer(JwtProperties props) {
        this(props, Clock.systemUTC(), new SecureRandom());
    }

    JwtTokenIssuer(JwtProperties props, Clock clock, SecureRandom random) {
        try {
            byte[] secret = props.secret().getBytes(StandardCharsets.UTF_8);
            this.signer = new MACSigner(secret);
            this.verifier = new MACVerifier(secret);
        } catch (JOSEException e) {
            throw new IllegalStateException("Invalid JWT secret for HS256", e);
        }
        this.clock = clock;
        this.random = random;
    }

    @Override
    public String signAccess(AccountId accountId) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(accountId.value()))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ACCESS_TTL)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
        return jwt.serialize();
    }

    @Override
    public String signRefresh() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public Optional<AccountId> verifyAccess(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(verifier)) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.toInstant().isBefore(clock.instant())) {
                return Optional.empty();
            }
            String sub = claims.getSubject();
            if (sub == null || sub.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new AccountId(Long.parseLong(sub)));
        } catch (ParseException | JOSEException | NumberFormatException e) {
            // Any malformed / wrong-signed / non-numeric-sub case folds to empty
            return Optional.empty();
        }
    }
}
