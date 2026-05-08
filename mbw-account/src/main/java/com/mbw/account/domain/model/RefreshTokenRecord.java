package com.mbw.account.domain.model;

import com.mbw.account.api.dto.DeviceType;
import com.mbw.account.api.dto.LoginMethod;
import java.time.Instant;
import java.util.Objects;

/**
 * Server-side record of an issued refresh token (Phase 1.3,
 * {@code account.refresh_token} V5 migration; device-management spec
 * V11 extends with five device-metadata columns).
 *
 * <p>Immutable: state changes (revoke) return a new instance. The id
 * is {@code null} for newly-constructed records (factory
 * {@link #createActive}); the repository assigns a real id on save and
 * downstream callers receive a record with {@link #id} populated.
 *
 * <p>Lifecycle predicates:
 *
 * <ul>
 *   <li>{@link #isActive(Instant)} — true iff {@code revokedAt == null}
 *       and {@code expiresAt} is in the future
 *   <li>{@link #revoke(Instant)} — must be called only once;
 *       double-revoke throws {@link IllegalStateException} so accidental
 *       double-rotation surfaces as a bug rather than silently
 *       overwriting the timestamp
 * </ul>
 *
 * <p>Device-metadata columns ({@code deviceId}, {@code deviceName},
 * {@code deviceType}, {@code ipAddress}, {@code loginMethod}) are
 * populated through the extended factory by the token-issuing UseCases.
 * The legacy four-arg factory remains in place as a deprecated
 * delegate during the wiring window — it fills required fields with the
 * documented degradation defaults (random UUID device id,
 * {@link DeviceType#UNKNOWN}, {@link LoginMethod#PHONE_SMS}).
 */
public final class RefreshTokenRecord {

    private final RefreshTokenRecordId id; // null until saved
    private final RefreshTokenHash tokenHash;
    private final AccountId accountId;
    private final DeviceId deviceId;
    private final DeviceName deviceName; // nullable
    private final DeviceType deviceType;
    private final IpAddress ipAddress; // nullable
    private final LoginMethod loginMethod;
    private final Instant expiresAt;
    private final Instant revokedAt; // null = active
    private final Instant createdAt;

    private RefreshTokenRecord(
            RefreshTokenRecordId id,
            RefreshTokenHash tokenHash,
            AccountId accountId,
            DeviceId deviceId,
            DeviceName deviceName,
            DeviceType deviceType,
            IpAddress ipAddress,
            LoginMethod loginMethod,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt) {
        this.id = id;
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId must not be null");
        this.deviceName = deviceName;
        this.deviceType = Objects.requireNonNull(deviceType, "deviceType must not be null");
        this.ipAddress = ipAddress;
        this.loginMethod = Objects.requireNonNull(loginMethod, "loginMethod must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.revokedAt = revokedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * Build a fresh active record with full device metadata
     * (device-management spec FR-008 / FR-009 / FR-012). The
     * token-issuing UseCases call this after extracting device hints
     * from the request headers.
     */
    public static RefreshTokenRecord createActive(
            RefreshTokenHash tokenHash,
            AccountId accountId,
            DeviceId deviceId,
            DeviceName deviceName,
            DeviceType deviceType,
            IpAddress ipAddress,
            LoginMethod loginMethod,
            Instant expiresAt,
            Instant now) {
        return new RefreshTokenRecord(
                /* id= */ null,
                tokenHash,
                accountId,
                deviceId,
                deviceName,
                deviceType,
                ipAddress,
                loginMethod,
                expiresAt,
                /* revokedAt= */ null,
                now);
    }

    /**
     * Reconstitute from persistence with full device metadata. Used by
     * {@code RefreshTokenMapper} after V11 wiring.
     */
    public static RefreshTokenRecord reconstitute(
            RefreshTokenRecordId id,
            RefreshTokenHash tokenHash,
            AccountId accountId,
            DeviceId deviceId,
            DeviceName deviceName,
            DeviceType deviceType,
            IpAddress ipAddress,
            LoginMethod loginMethod,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt) {
        Objects.requireNonNull(id, "id must not be null");
        return new RefreshTokenRecord(
                id,
                tokenHash,
                accountId,
                deviceId,
                deviceName,
                deviceType,
                ipAddress,
                loginMethod,
                expiresAt,
                revokedAt,
                createdAt);
    }

    /**
     * Legacy four-arg factory retained until T9 wiring updates the
     * existing token-issuing UseCases. Defaults follow the
     * device-management degradation policy: random UUID device id
     * (CL-001 (a)), {@code UNKNOWN} type, {@code PHONE_SMS} login
     * method, no name, no IP. New code must call the nine-arg factory.
     *
     * @deprecated migrate to
     *     {@link #createActive(RefreshTokenHash, AccountId, DeviceId, DeviceName, DeviceType, IpAddress,
     *     LoginMethod, Instant, Instant)} as part of T9.
     */
    @Deprecated
    public static RefreshTokenRecord createActive(
            RefreshTokenHash tokenHash, AccountId accountId, Instant expiresAt, Instant now) {
        return createActive(
                tokenHash,
                accountId,
                DeviceId.fromHeaderOrFallback(null),
                /* deviceName */ null,
                DeviceType.UNKNOWN,
                /* ipAddress */ null,
                LoginMethod.PHONE_SMS,
                expiresAt,
                now);
    }

    /**
     * Legacy six-arg reconstitute retained until T5 mapper wiring lifts
     * the device columns out of persistence. Defaults match the
     * deprecated {@link #createActive(RefreshTokenHash, AccountId, Instant, Instant)} form.
     *
     * @deprecated migrate to
     *     {@link #reconstitute(RefreshTokenRecordId, RefreshTokenHash, AccountId, DeviceId, DeviceName,
     *     DeviceType, IpAddress, LoginMethod, Instant, Instant, Instant)} once the JPA mapper reads the
     *     device columns.
     */
    @Deprecated
    public static RefreshTokenRecord reconstitute(
            RefreshTokenRecordId id,
            RefreshTokenHash tokenHash,
            AccountId accountId,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt) {
        return reconstitute(
                id,
                tokenHash,
                accountId,
                DeviceId.fromHeaderOrFallback(null),
                /* deviceName */ null,
                DeviceType.UNKNOWN,
                /* ipAddress */ null,
                LoginMethod.PHONE_SMS,
                expiresAt,
                revokedAt,
                createdAt);
    }

    public RefreshTokenRecordId id() {
        return id;
    }

    public RefreshTokenHash tokenHash() {
        return tokenHash;
    }

    public AccountId accountId() {
        return accountId;
    }

    public DeviceId deviceId() {
        return deviceId;
    }

    public DeviceName deviceName() {
        return deviceName;
    }

    public DeviceType deviceType() {
        return deviceType;
    }

    public IpAddress ipAddress() {
        return ipAddress;
    }

    public LoginMethod loginMethod() {
        return loginMethod;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /**
     * Return a new record with {@code revokedAt = at}, preserving all
     * device metadata. Throws if the record is already revoked
     * (idempotent revoke would mask accidental double-rotation bugs).
     */
    public RefreshTokenRecord revoke(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (revokedAt != null) {
            throw new IllegalStateException("Already revoked at " + revokedAt);
        }
        return new RefreshTokenRecord(
                id,
                tokenHash,
                accountId,
                deviceId,
                deviceName,
                deviceType,
                ipAddress,
                loginMethod,
                expiresAt,
                at,
                createdAt);
    }

    public boolean isActive(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
