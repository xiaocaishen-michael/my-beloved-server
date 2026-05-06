package com.mbw.account.application.command;

import com.mbw.account.domain.model.AccountId;

/**
 * Input to {@code SendDeletionCodeUseCase}: send a deletion-confirmation
 * SMS code to the authenticated account's registered phone.
 */
public record SendDeletionCodeCommand(AccountId accountId, String clientIp) {}
