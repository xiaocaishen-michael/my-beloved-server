package com.mbw.account.api.event;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.DeviceId;
import com.mbw.account.domain.model.RefreshTokenRecordId;
import java.time.Instant;

/**
 * Published (via Spring Modulith outbox) when a user revokes one of
 * their device sessions through the device-management endpoint
 * (device-management spec FR-017).
 *
 * <p>The current PR has no in-process subscriber — the event is
 * persisted via the Event Publication Registry so a future security
 * audit module ({@code mbw-audit}) or notification module
 * ({@code mbw-notify}) can read "您在 X 设备的登录已被移除" pushes off
 * the outbox without coupling back to {@code mbw-account}'s internal
 * types. Placed in {@code api.event} per
 * {@code modular-strategy.md § 跨模块通信规则}.
 *
 * @param accountId  the account whose device was revoked
 * @param recordId   the {@code refresh_token} row that was revoked
 * @param deviceId   the device identifier preserved on the row
 * @param revokedAt  the {@code revoked_at} timestamp written to the
 *                   row (= now() at the use case call site)
 * @param occurredAt domain event timestamp (= {@code revokedAt} for
 *                   this event; kept distinct so subscribers don't
 *                   conflate "when it happened" with "when we
 *                   noticed")
 */
public record DeviceRevokedEvent(
        AccountId accountId, RefreshTokenRecordId recordId, DeviceId deviceId, Instant revokedAt, Instant occurredAt) {}
