package com.mbw.account.api.event;

import com.mbw.account.domain.model.AccountId;
import java.time.Instant;

/**
 * Published (via Spring Modulith outbox) when an account transitions
 * FROZEN → ANONYMIZED on the scheduled anonymization run
 * (anonymize-frozen-accounts spec FR-008).
 *
 * <p>Terminal counterpart to {@link AccountDeletionRequestedEvent}: a
 * downstream subscriber that quiesced PII writes on FREEZE should now
 * delete its data for the account (mbw-pkm notes, mbw-inspire goals,
 * etc.). Placed in {@code api.event} so future modules can subscribe
 * through the public API boundary without depending on internal domain
 * types (per modular-strategy § 跨模块通信规则).
 *
 * @param accountId     the account that was anonymized
 * @param anonymizedAt  when the FROZEN → ANONYMIZED transition committed
 * @param occurredAt    domain event timestamp (same as
 *                      {@code anonymizedAt} for this event)
 */
public record AccountAnonymizedEvent(AccountId accountId, Instant anonymizedAt, Instant occurredAt) {}
