package com.mbw.account.application.command;

import com.mbw.account.domain.model.AccountId;
import java.util.Objects;

/**
 * Input for {@code LogoutAllSessionsUseCase} (Phase 1.4).
 *
 * <p>{@code accountId} is resolved by the controller from the
 * {@code Authorization: Bearer} access token (FR-003 / spec.md plan
 * § Web Layer); {@code clientIp} feeds the
 * {@code logout-all:&lt;ip&gt;} rate-limit bucket (FR-006).
 */
public record LogoutAllSessionsCommand(AccountId accountId, String clientIp) {

    public LogoutAllSessionsCommand {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(clientIp, "clientIp must not be null");
    }
}
