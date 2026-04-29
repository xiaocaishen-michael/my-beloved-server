/**
 * JPA persistence adapters for the account module.
 *
 * <p>{@code AccountJpaEntity} / {@code CredentialJpaEntity} mirror the
 * V2 migration tables; {@code AccountJpaRepository} /
 * {@code CredentialJpaRepository} are package-private Spring Data
 * interfaces consumed only by {@code AccountRepositoryImpl}, which
 * adapts JPA back to the {@code AccountRepository} domain contract.
 * Conversion is hand-rolled in {@code AccountMapper}.
 *
 * <p>Domain-side callers should never reference types from this
 * package directly; the {@code DomainLayerBoundaryTest} ArchUnit rule
 * fails CI if they do.
 */
package com.mbw.account.infrastructure.persistence;
