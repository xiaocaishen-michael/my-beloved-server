package com.mbw.account.application.command;

import com.mbw.account.domain.model.AccountId;

public record InitiateRealnameCommand(
        AccountId accountId, String realName, String idCardNo, String agreementVersion, String clientIp) {}
