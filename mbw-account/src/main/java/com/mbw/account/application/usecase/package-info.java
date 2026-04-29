/**
 * Application use cases — orchestrate domain services + repositories
 * + cross-module APIs (per CLAUDE.md § 二, naming
 * {@code <Verb><Noun>UseCase}). Each use case is the single
 * application-layer entry point for one user-facing operation.
 *
 * <p>Use cases sit between {@code web} controllers (HTTP adapters)
 * and {@code domain} (pure business rules) — they own transactional
 * boundaries and cross-aggregate orchestration but never own
 * business invariants directly (those live on aggregates and domain
 * services).
 */
package com.mbw.account.application.usecase;
