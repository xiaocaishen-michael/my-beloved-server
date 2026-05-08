package com.mbw.account.application.command;

import com.mbw.account.domain.model.AccountId;

public record ConfirmRealnameCommand(AccountId callerAccountId, String providerBizId) {}
