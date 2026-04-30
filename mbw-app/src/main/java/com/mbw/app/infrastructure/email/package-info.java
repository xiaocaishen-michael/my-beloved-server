/**
 * Email gateway implementations for the deployment unit.
 *
 * <p>Provider-selectable via {@code mbw.email.provider}:
 *
 * <ul>
 *   <li>{@code resend} — {@link com.mbw.app.infrastructure.email.ResendEmailClient}
 *       (production HTTPS API path, with Resilience4j retry around
 *       transient 5xx / IO failures)
 *   <li>{@code log} (or unset) — {@link com.mbw.app.infrastructure.email.LoggingEmailSender}
 *       (dev / test fallback that only logs)
 * </ul>
 *
 * <p>Mirrors the {@code mbw.sms.provider} selection pattern used in
 * {@link com.mbw.app.infrastructure.sms} — keeping both abstractions on
 * the same selection model (explicit {@code havingValue} +
 * {@code matchIfMissing} on the fallback) avoids
 * {@code @ConditionalOnMissingBean} race conditions across multiple
 * candidate beans.
 */
package com.mbw.app.infrastructure.email;
