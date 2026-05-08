package com.mbw.account.domain.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Mainland-PRC 18-digit ID card validator (GB 11643), realname-verification
 * spec T2.
 *
 * <p>Stateless utility — exposed via a single static method to match the call
 * style in {@code InitiateRealnameVerificationUseCase} (T13). Returns
 * {@code boolean} rather than throwing, since the use case maps the boolean
 * to the domain {@code InvalidIdCardFormatException} alongside other
 * pre-flight checks.
 *
 * <p>Validation order is intentionally cheapest-first → most-expensive: a
 * malformed length / character is rejected before we touch
 * {@link java.time.LocalDate} or do the GB 11643 weighted sum. This keeps the
 * hot path (happy id cards) at a single arithmetic loop.
 *
 * <ol>
 *   <li>length == 18
 *   <li>first 17 chars are digits, the 18th is digit or upper-case 'X'
 *   <li>administrative-division prefix not '00' (no PRC province uses it)
 *   <li>embedded birth date (yyyyMMdd at offset 6..14) is a real calendar date
 *   <li>GB 11643 weighted check digit matches the trailing char
 * </ol>
 */
public final class IdentityNumberValidator {

    /** GB 11643 weights for digits 0..16 (length 17). */
    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /** GB 11643 check-digit table — index = weighted sum mod 11. */
    private static final char[] CHECK_TABLE = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    /**
     * Pattern uses {@code uuuu} (proleptic-year) instead of {@code yyyy} (year-of-era),
     * because {@link ResolverStyle#STRICT} requires an era field for {@code yyyy} and
     * the ID-card embedded date carries none. {@code uuuu} + STRICT correctly rejects
     * impossible calendar dates like Feb 30 without demanding an era token.
     */
    private static final DateTimeFormatter BIRTH_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private IdentityNumberValidator() {}

    /**
     * Validate a candidate ID-card string. Returns {@code false} for any
     * defect — null, blank, wrong length, malformed characters, illegal
     * province prefix, impossible date, or wrong check digit. Never throws.
     */
    public static boolean validate(String idCardNo) {
        if (idCardNo == null || idCardNo.length() != 18) {
            return false;
        }
        // First 17 must be digits
        for (int i = 0; i < 17; i++) {
            if (!Character.isDigit(idCardNo.charAt(i))) {
                return false;
            }
        }
        // Last char must be digit or 'X'
        char last = idCardNo.charAt(17);
        if (!Character.isDigit(last) && last != 'X') {
            return false;
        }
        // Administrative-division prefix '00' is not assigned to any PRC province
        if (idCardNo.charAt(0) == '0' && idCardNo.charAt(1) == '0') {
            return false;
        }
        // Birth date at offset 6..14 must be a real calendar date (Feb 30 etc. rejected)
        try {
            LocalDate.parse(idCardNo.substring(6, 14), BIRTH_FORMAT);
        } catch (DateTimeParseException e) {
            return false;
        }
        // GB 11643 weighted check digit
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (idCardNo.charAt(i) - '0') * WEIGHTS[i];
        }
        return CHECK_TABLE[sum % 11] == last;
    }
}
