package com.mbw.account.application.command;

import com.mbw.account.domain.model.AccountId;

/**
 * Input for the {@code PATCH /me} use case.
 *
 * <p>{@code rawDisplayName} is the unvalidated client-supplied string;
 * the use case constructs a {@link com.mbw.account.domain.model.DisplayName}
 * from it inside its rate-limit/auth gates so a malformed value still
 * counts toward the rate-limit bucket.
 */
public record UpdateDisplayNameCommand(AccountId accountId, String rawDisplayName) {}
