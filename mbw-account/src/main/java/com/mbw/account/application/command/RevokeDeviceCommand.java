package com.mbw.account.application.command;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.DeviceId;
import com.mbw.account.domain.model.RefreshTokenRecordId;
import java.util.Objects;

/**
 * Input for {@code RevokeDeviceUseCase} (device-management spec
 * FR-003 / FR-005 / FR-013).
 *
 * <p>{@code recordId} identifies the row to revoke; {@code currentDeviceId}
 * is read from the caller's access token {@code did} claim and used to
 * reject the FR-005 self-revoke attempt. {@code clientIp} feeds the
 * IP-dimension rate-limit bucket; may be {@code null}.
 */
public record RevokeDeviceCommand(
        AccountId accountId, RefreshTokenRecordId recordId, DeviceId currentDeviceId, String clientIp) {

    public RevokeDeviceCommand {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(recordId, "recordId must not be null");
        Objects.requireNonNull(currentDeviceId, "currentDeviceId must not be null");
    }
}
