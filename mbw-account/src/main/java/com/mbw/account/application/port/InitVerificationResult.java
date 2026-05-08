package com.mbw.account.application.port;

/**
 * Initiate-verification result DTO — the upstream-issued URL the client
 * loads in its WebView / SDK to perform liveness detection.
 *
 * @param livenessUrl absolute https URL; for {@code BypassRealnameClient}
 *     the value follows the synthetic {@code bypass://verified} /
 *     {@code bypass://failed} scheme so dev clients can short-circuit
 */
public record InitVerificationResult(String livenessUrl) {}
