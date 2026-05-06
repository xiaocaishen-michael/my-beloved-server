package com.mbw.account.api.event;

import com.mbw.account.domain.model.AccountId;
import java.time.Instant;

/**
 * Published (via Spring Modulith outbox) when an account transitions
 * FROZEN → ACTIVE on cancel-deletion (cancel-deletion spec FR-007).
 *
 * <p>Counterpart to {@link AccountDeletionRequestedEvent}: a downstream
 * subscriber that quiesced PII writes on FREEZE may resume on receipt
 * of this event. Placed in {@code api.event} so future modules can
 * subscribe through the public API boundary without depending on
 * internal domain types (per modular-strategy § 跨模块通信规则).
 *
 * @param accountId   the account whose deletion was cancelled
 * @param cancelledAt when the FROZEN → ACTIVE transition occurred
 * @param occurredAt  domain event timestamp (same as {@code cancelledAt}
 *                    for this event)
 */
public record AccountDeletionCancelledEvent(AccountId accountId, Instant cancelledAt, Instant occurredAt) {}
