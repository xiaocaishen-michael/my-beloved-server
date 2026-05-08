package com.mbw.account.infrastructure.persistence;

import com.mbw.account.api.dto.DeviceType;
import com.mbw.account.api.dto.LoginMethod;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.DeviceId;
import com.mbw.account.domain.model.DeviceName;
import com.mbw.account.domain.model.IpAddress;
import com.mbw.account.domain.model.RefreshTokenHash;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.model.RefreshTokenRecordId;

/**
 * Hand-rolled domain ↔ JPA mapper for {@link RefreshTokenRecord} —
 * same pattern as {@link AccountMapper} (no MapStruct, the conversion
 * is small enough that explicit code is clearer than annotation
 * processor configuration).
 *
 * <p>Device-metadata columns added by V11 are mapped here. Nullable
 * wrappers (device_name, ip_address) round-trip through
 * {@link DeviceName#ofNullable} and {@link IpAddress#ofNullable} so a
 * blank or absent column produces {@code null} in the domain object.
 */
public final class RefreshTokenMapper {

    private RefreshTokenMapper() {}

    public static RefreshTokenRecord toDomain(RefreshTokenJpaEntity entity) {
        DeviceName deviceName = entity.getDeviceName() == null ? null : DeviceName.ofNullable(entity.getDeviceName());
        IpAddress ipAddress = entity.getIpAddress() == null ? null : IpAddress.ofNullable(entity.getIpAddress());
        return RefreshTokenRecord.reconstitute(
                new RefreshTokenRecordId(entity.getId()),
                new RefreshTokenHash(entity.getTokenHash()),
                new AccountId(entity.getAccountId()),
                new DeviceId(entity.getDeviceId()),
                deviceName,
                DeviceType.valueOf(entity.getDeviceType()),
                ipAddress,
                LoginMethod.valueOf(entity.getLoginMethod()),
                entity.getExpiresAt(),
                entity.getRevokedAt(),
                entity.getCreatedAt());
    }

    public static RefreshTokenJpaEntity toEntity(RefreshTokenRecord record) {
        RefreshTokenJpaEntity entity = new RefreshTokenJpaEntity();
        if (record.id() != null) {
            entity.setId(record.id().value());
        }
        entity.setTokenHash(record.tokenHash().value());
        entity.setAccountId(record.accountId().value());
        entity.setDeviceId(record.deviceId().value());
        entity.setDeviceName(
                record.deviceName() == null ? null : record.deviceName().value());
        entity.setDeviceType(record.deviceType().name());
        entity.setIpAddress(
                record.ipAddress() == null ? null : record.ipAddress().value());
        entity.setLoginMethod(record.loginMethod().name());
        entity.setExpiresAt(record.expiresAt());
        entity.setRevokedAt(record.revokedAt());
        entity.setCreatedAt(record.createdAt());
        return entity;
    }
}
