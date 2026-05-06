package com.mbw.account.api.event;

import com.mbw.account.domain.model.AccountId;
import java.time.Instant;

/**
 * Published (via Spring Modulith outbox) when an account transitions
 * ACTIVE → FROZEN on the first step of the delete-account flow
 * (delete-account spec FR-008).
 *
 * <p>Placed in {@code api.event} so future modules (e.g. {@code mbw-pkm})
 * can subscribe through the public API boundary without depending on
 * internal domain types (per modular-strategy § 跨模块通信规则).
 *
 * @param accountId  the account that initiated deletion
 * @param freezeAt   when the FROZEN transition occurred (= now() at call
 *                   site)
 * @param freezeUntil when the account becomes eligible for anonymization
 *                   (= freezeAt + 15 days)
 * @param occurredAt domain event timestamp (same as {@code freezeAt} for
 *                   this event)
 */
public record AccountDeletionRequestedEvent(
        AccountId accountId, Instant freezeAt, Instant freezeUntil, Instant occurredAt) {}
