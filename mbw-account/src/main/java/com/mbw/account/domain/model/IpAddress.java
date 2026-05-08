package com.mbw.account.domain.model;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * IPv4 or IPv6 literal address recorded with each {@code refresh_token}
 * row at insert time (device-management spec FR-007 / FR-010).
 *
 * <p>Input is pre-screened by regex to ensure only digits, hex, dots,
 * and colons reach {@link InetAddress#getByName} — the JDK's literal-IP
 * parser would otherwise resolve hostnames via DNS. After validation
 * the input is preserved verbatim so that downstream display matches
 * what was observed at the network edge.
 *
 * <p>{@link #isPrivate()} returns {@code true} for any address that
 * should be filtered to {@code NULL} before persistence:
 *
 * <ul>
 *   <li>IPv4 — {@code 10/8} site-local, {@code 172.16/12} site-local,
 *       {@code 192.168/16} site-local, {@code 127/8} loopback,
 *       {@code 169.254/16} link-local
 *   <li>IPv6 — {@code ::1} loopback, {@code fe80::/10} link-local,
 *       {@code fc00::/7} unique-local (RFC 4193) — the JDK's
 *       {@code isSiteLocalAddress} only covers the deprecated
 *       {@code fec0::/10} block, so the ULA range is checked explicitly
 * </ul>
 */
public record IpAddress(String value) {

    private static final Pattern IP_LITERAL_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");

    public IpAddress {
        Objects.requireNonNull(value, "ipAddress must not be null");
        if (!IP_LITERAL_CHARS.matcher(value).matches()) {
            throw new IllegalArgumentException("INVALID_IP_ADDRESS: " + value);
        }
        try {
            InetAddress parsed = InetAddress.getByName(value);
            if (!(parsed instanceof Inet4Address) && !(parsed instanceof Inet6Address)) {
                throw new IllegalArgumentException("INVALID_IP_ADDRESS: " + value);
            }
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("INVALID_IP_ADDRESS: " + value, ex);
        }
    }

    /**
     * Wrap an optional raw value (typically the value extracted from
     * {@code X-Forwarded-For} or {@code RemoteAddr}). Returns
     * {@code null} when the input is {@code null}, blank, or fails IP
     * literal validation — the request still proceeds, the row simply
     * persists {@code NULL}.
     */
    public static IpAddress ofNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new IpAddress(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Return {@code true} when the address falls in any reserved private / loopback / link-local range. */
    public boolean isPrivate() {
        InetAddress addr = parseUnchecked();
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
            return true;
        }
        if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
            return true;
        }
        if (addr instanceof Inet6Address) {
            byte first = addr.getAddress()[0];
            // RFC 4193 ULA: fc00::/7 — first byte 0xFC or 0xFD.
            return (first & 0xFE) == 0xFC;
        }
        return false;
    }

    private InetAddress parseUnchecked() {
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException ex) {
            // Invariant: constructor already validated, so this branch is unreachable.
            throw new IllegalStateException("ipAddress invariant broken: " + value, ex);
        }
    }
}
