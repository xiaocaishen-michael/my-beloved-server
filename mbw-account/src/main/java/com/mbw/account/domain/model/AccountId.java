package com.mbw.account.domain.model;

/**
 * Strongly-typed identifier for {@code Account}.
 *
 * <p>Wraps the database-generated {@code BIGINT IDENTITY} so business
 * code never confuses an account id with another numeric id (e.g.
 * credential id, JWT subject as string). The wrapping also gives us a
 * single place to enforce the positive-id invariant.
 */
public record AccountId(long value) {

    public AccountId {
        if (value <= 0L) {
            throw new IllegalArgumentException("AccountId must be positive, got " + value);
        }
    }
}
