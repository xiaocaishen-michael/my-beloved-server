package com.mbw.account.application.command;

import com.mbw.account.domain.model.AccountId;

/**
 * Input to {@code DeleteAccountUseCase}: submit the 6-digit SMS code to
 * confirm account deletion and trigger the ACTIVE → FROZEN transition.
 */
public record DeleteAccountCommand(AccountId accountId, String code, String clientIp) {}
