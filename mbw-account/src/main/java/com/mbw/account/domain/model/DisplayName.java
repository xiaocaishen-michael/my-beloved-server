package com.mbw.account.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Account-facing display name value object.
 *
 * <p>Constructed from arbitrary user input; the compact constructor enforces
 * specs/account/profile/spec.md FR-005:
 *
 * <ul>
 *   <li>length: 1-32 Unicode codepoints after trim
 *   <li>charset: rejects ASCII/Unicode control characters, zero-width
 *       characters, and line separators (U+2028 / U+2029)
 *   <li>storage: trimmed value (leading/trailing whitespace removed)
 * </ul>
 *
 * <p>Codepoint counting (rather than {@code String.length()}) keeps CJK and
 * emoji users from being penalised — a 4-byte emoji counts as one codepoint
 * exactly like a single ASCII letter.
 *
 * <p>Validation failures throw {@link IllegalArgumentException} prefixed with
 * {@code INVALID_DISPLAY_NAME}, picked up by
 * {@code mbw-shared} {@code GlobalExceptionHandler} and mapped to HTTP 400.
 */
public record DisplayName(String value) {

    private static final int MIN_CODEPOINTS = 1;
    private static final int MAX_CODEPOINTS = 32;

    /**
     * Single-pass deny-list covering the FR-005 forbidden character classes:
     *
     * <ul>
     *   <li>U+0000-U+001F + U+007F-U+009F — C0/C1 control
     *   <li>U+200B-U+200F + U+2060-U+2064 + U+FEFF — zero-width family
     *   <li>U+2028 / U+2029 — line/paragraph separators
     * </ul>
     */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "[\\u0000-\\u001F\\u007F\\u0080-\\u009F\\u200B-\\u200F\\u2028\\u2029\\u2060-\\u2064\\uFEFF]");

    public DisplayName {
        Objects.requireNonNull(value, "value must not be null");
        String trimmed = value.trim();
        int codepoints = trimmed.codePointCount(0, trimmed.length());
        if (codepoints < MIN_CODEPOINTS || codepoints > MAX_CODEPOINTS) {
            throw new IllegalArgumentException(
                    "INVALID_DISPLAY_NAME: codepoint length " + codepoints + " not in [1, 32]");
        }
        if (FORBIDDEN.matcher(trimmed).find()) {
            throw new IllegalArgumentException("INVALID_DISPLAY_NAME: contains forbidden character");
        }
        value = trimmed;
    }
}
