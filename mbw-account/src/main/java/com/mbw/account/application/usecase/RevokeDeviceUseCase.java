package com.mbw.account.application.usecase;

import com.mbw.account.api.event.DeviceRevokedEvent;
import com.mbw.account.application.command.RevokeDeviceCommand;
import com.mbw.account.domain.exception.CannotRemoveCurrentDeviceException;
import com.mbw.account.domain.exception.DeviceNotFoundException;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revoke one device session of the authenticated account
 * (device-management spec FR-003 / FR-005 / FR-013 / FR-014 /
 * FR-017).
 *
 * <p>Per-account 5/min + per-IP 20/min rate limits gate the call.
 * The use case then loads the row by id, returns 404 if missing or
 * if it belongs to another account (anti-enumeration, byte-identical
 * branches), 409 if the row's {@code deviceId} matches the caller's
 * current {@code did} claim (FR-005), and silently 200's if the row
 * is already revoked (idempotence). The successful path persists the
 * revoke and publishes {@link DeviceRevokedEvent} via the Spring
 * Modulith outbox; both DB save and event publish are inside a single
 * {@code @Transactional(rollbackFor = Throwable.class)} boundary so
 * either failure rolls back atomically (FR-017 / SC-003).
 */
@Service
public class RevokeDeviceUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(RevokeDeviceUseCase.class);

    static final String ACCOUNT_RATE_LIMIT_KEY_PREFIX = "device-revoke:account:";
    static final String IP_RATE_LIMIT_KEY_PREFIX = "device-revoke:ip:";

    static final Bandwidth DEVICE_REVOKE_PER_ACCOUNT_60S = Bandwidth.builder()
            .capacity(5)
            .refillIntervally(5, Duration.ofSeconds(60))
            .build();
    static final Bandwidth DEVICE_REVOKE_PER_IP_60S = Bandwidth.builder()
            .capacity(20)
            .refillIntervally(20, Duration.ofSeconds(60))
            .build();

    private final RateLimitService rateLimitService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /** Spring-injected constructor — no Clock bean exists, default to system UTC. */
    @Autowired
    public RevokeDeviceUseCase(
            RateLimitService rateLimitService,
            RefreshTokenRepository refreshTokenRepository,
            ApplicationEventPublisher eventPublisher) {
        this(rateLimitService, refreshTokenRepository, eventPublisher, Clock.systemUTC());
    }

    /** Test-friendly constructor accepting a fixed clock. */
    RevokeDeviceUseCase(
            RateLimitService rateLimitService,
            RefreshTokenRepository refreshTokenRepository,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.rateLimitService = rateLimitService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Throwable.class)
    public void execute(RevokeDeviceCommand cmd) {
        rateLimitService.consumeOrThrow(
                ACCOUNT_RATE_LIMIT_KEY_PREFIX + cmd.accountId().value(), DEVICE_REVOKE_PER_ACCOUNT_60S);
        if (cmd.clientIp() != null) {
            rateLimitService.consumeOrThrow(IP_RATE_LIMIT_KEY_PREFIX + cmd.clientIp(), DEVICE_REVOKE_PER_IP_60S);
        }

        RefreshTokenRecord record =
                refreshTokenRepository.findById(cmd.recordId()).orElseThrow(DeviceNotFoundException::new);

        // Anti-enumeration (FR-014): cross-account access folds to the same 404 as a missing id.
        if (!record.accountId().equals(cmd.accountId())) {
            throw new DeviceNotFoundException();
        }

        // FR-005: server-side check rejects deletion of the caller's own device.
        if (record.deviceId().equals(cmd.currentDeviceId())) {
            throw new CannotRemoveCurrentDeviceException();
        }

        // Idempotent (FR-003): already-revoked row → no-op 200, no event.
        if (record.revokedAt() != null) {
            LOG.info(
                    "device.revoke.idempotent accountId={} recordId={} deviceId={}",
                    cmd.accountId().value(),
                    cmd.recordId().value(),
                    shortDeviceId(record));
            return;
        }

        // Atomic UPDATE … WHERE revoked_at IS NULL guards against the read-then-write
        // race that bare findById + save would have (T16): N concurrent revokes all
        // pass the in-memory revokedAt==null check, but only the first reaches DB
        // affecting 1 row; the rest get affected=0 and surface as idempotent 200
        // with no event published (FR-003 / SC-003).
        Instant now = clock.instant();
        int affected = refreshTokenRepository.revoke(cmd.recordId(), now);
        if (affected == 0) {
            LOG.info(
                    "device.revoke.lost-race accountId={} recordId={} deviceId={}",
                    cmd.accountId().value(),
                    cmd.recordId().value(),
                    shortDeviceId(record));
            return;
        }
        eventPublisher.publishEvent(
                new DeviceRevokedEvent(record.accountId(), record.id(), record.deviceId(), now, now));
        LOG.info(
                "device.revoke accountId={} recordId={} deviceId={}",
                cmd.accountId().value(),
                cmd.recordId().value(),
                shortDeviceId(record));
    }

    private static String shortDeviceId(RefreshTokenRecord record) {
        // Log only the first 8 chars (UUID prefix) to avoid leaking the full device fingerprint.
        return record.deviceId().value().substring(0, 8);
    }
}
