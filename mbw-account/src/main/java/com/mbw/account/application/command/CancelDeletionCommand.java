package com.mbw.account.application.command;

import com.mbw.account.web.resolver.DeviceMetadata;

/**
 * Input to {@code CancelDeletionUseCase}: a public, unauthed request to
 * submit a 6-digit CANCEL_DELETION SMS code, transition FROZEN → ACTIVE,
 * and issue a fresh access / refresh token pair (cancel-deletion spec
 * § Endpoint 2).
 *
 * <p>{@code deviceMetadata} carries the X-Device-* header triplet
 * (device-management spec FR-009); may be {@code null} for legacy
 * callers — the UseCase falls back to a synthesised triplet.
 */
public record CancelDeletionCommand(String phone, String code, String clientIp, DeviceMetadata deviceMetadata) {

    /** Backward-compat overload — synthesises a fallback metadata triplet. */
    public CancelDeletionCommand(String phone, String code, String clientIp) {
        this(phone, code, clientIp, /* deviceMetadata */ null);
    }
}
