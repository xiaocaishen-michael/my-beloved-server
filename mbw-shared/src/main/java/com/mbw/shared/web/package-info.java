/**
 * Cross-cutting web concerns shared across business modules.
 *
 * <p>Contains framework infrastructure that every module benefits from but
 * shouldn't reimplement: request correlation, global exception mapping to
 * RFC 9457 ProblemDetail, rate-limit framework integration.
 *
 * <p>Business modules' own controllers / advices live under
 * {@code com.mbw.<module>.web}, not here.
 */
package com.mbw.shared.web;
