package com.mbw.account.domain.model;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stable client-side device identifier persisted on each
 * {@code refresh_token} row (device-management spec FR-007 / FR-009 /
 * CL-001 (a)).
 *
 * <p>Canonical form is the lowercase hyphenated UUID emitted by
 * {@link UUID#toString()}: {@code 8-4-4-4-12} hex digits. The client
 * generates a random UUID v4 on first launch, persists it in secure
 * storage, and sends it on every token-issuing request as
 * {@code X-Device-Id}. Uppercase, surrounding whitespace, and other
 * formats are rejected by the canonical constructor — callers must
 * route header input through {@link #fromHeaderOrFallback} to inherit
 * the documented degradation policy.
 *
 * <p>{@code fromHeaderOrFallback} accepts {@code null} / blank /
 * malformed input and substitutes a server-generated UUID v4. This is
 * the M1.X tradeoff per CL-001 (a): degraded clients still produce a
 * valid row at the cost of UI-list noise — the device row is created
 * but the id will not match a future request from the same client.
 */
public record DeviceId(String value) {

    private static final Pattern UUID_LOWER =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public DeviceId {
        Objects.requireNonNull(value, "deviceId must not be null");
        if (!UUID_LOWER.matcher(value).matches()) {
            throw new IllegalArgumentException("INVALID_DEVICE_ID: " + value);
        }
    }

    /**
     * Build a {@code DeviceId} from an HTTP {@code X-Device-Id} header.
     * Falls back to a server-generated UUID v4 when the header is
     * missing, blank, or fails canonical validation.
     */
    public static DeviceId fromHeaderOrFallback(String header) {
        if (header == null || header.isBlank()) {
            return new DeviceId(UUID.randomUUID().toString());
        }
        String trimmed = header.trim();
        if (!UUID_LOWER.matcher(trimmed).matches()) {
            return new DeviceId(UUID.randomUUID().toString());
        }
        return new DeviceId(trimmed);
    }
}
