package com.mbw.account.infrastructure.security;

import com.mbw.account.domain.model.PasswordHash;
import com.mbw.account.domain.service.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt-backed implementation of {@link PasswordHasher}.
 *
 * <p>Cost = 8 per {@code specs/auth/register-by-phone/plan.md} —
 * balances per-request CPU cost against the FR-013 timing-defense
 * budget (constant-time wrapper pads to 400ms, leaving ~50ms of
 * margin even on the busiest path that hashes a password). Future
 * tuning can lift the cost in a new BCrypt round; bcrypt's encoded
 * format (which records its own cost) keeps existing hashes verifiable.
 */
@Component
public class BCryptPasswordHasher implements PasswordHasher {

    private static final int COST = 8;

    private final BCryptPasswordEncoder encoder;

    public BCryptPasswordHasher() {
        this.encoder = new BCryptPasswordEncoder(COST);
    }

    @Override
    public PasswordHash hash(String plaintext) {
        return new PasswordHash(encoder.encode(plaintext));
    }

    @Override
    public boolean matches(String plaintext, PasswordHash hash) {
        return encoder.matches(plaintext, hash.value());
    }
}
