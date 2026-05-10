package com.mbw.account.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pepper for the realname-verification ID card hash
 * ({@code idCardHash = sha256(idCardNo || pepper)}, FR-013 / plan.md
 * § InitiateRealnameVerificationUseCase step 6).
 *
 * <p>Bound to {@code mbw.realname.pepper.value} via the
 * {@code MBW_REALNAME_PEPPER_VALUE} environment variable. The {@code _VALUE}
 * suffix matters — Spring relaxed binding maps {@code MBW_REALNAME_PEPPER}
 * to property {@code mbw.realname.pepper} (one level shallower) and misses
 * this record's {@code value} field. Distinct from the
 * AES-GCM data encryption key ({@code mbw.realname.dek.base64}) because
 * the pepper is fed into a one-way hash (deterministic, used for
 * cross-account uniqueness lookups), whereas the DEK is for reversible
 * AES-GCM PII encryption.
 *
 * <p>No {@code @NotBlank} per project convention (see
 * {@code MockSmsProperties} javadoc) — validation lives at the consumer
 * side ({@link com.mbw.account.application.service.IdentityHashService}
 * constructor) so dev / test profiles boot without failing here when
 * the pepper isn't set in those environments.
 */
@ConfigurationProperties(prefix = "mbw.realname.pepper")
public record RealnamePepperProperties(String value) {}
