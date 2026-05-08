package com.mbw.account.web.exception;

import com.mbw.account.domain.exception.AccountInFreezePeriodException;
import com.mbw.account.domain.exception.AccountInactiveException;
import com.mbw.account.domain.exception.AccountNotFoundException;
import com.mbw.account.domain.exception.AgreementRequiredException;
import com.mbw.account.domain.exception.AlreadyVerifiedException;
import com.mbw.account.domain.exception.CannotRemoveCurrentDeviceException;
import com.mbw.account.domain.exception.DeviceNotFoundException;
import com.mbw.account.domain.exception.IdCardOccupiedException;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.domain.exception.InvalidDeletionCodeException;
import com.mbw.account.domain.exception.InvalidIdCardFormatException;
import com.mbw.account.domain.exception.InvalidPhoneFormatException;
import com.mbw.account.domain.exception.ProviderErrorException;
import com.mbw.account.domain.exception.ProviderTimeoutException;
import com.mbw.account.domain.exception.RealnameProfileAccessDeniedException;
import com.mbw.account.domain.exception.RealnameProfileNotFoundException;
import com.mbw.account.domain.exception.WeakPasswordException;
import com.mbw.shared.api.sms.SmsSendException;
import com.mbw.shared.web.RateLimitedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Account-module exception → HTTP ProblemDetail mapping (FR-007 +
 * FR-010).
 *
 * <p>{@link Order#value} is {@link Ordered#HIGHEST_PRECEDENCE} + 100
 * (per A3 in tasks.md): high enough to override the catch-all in
 * {@code mbw-shared.web.GlobalExceptionHandler} (which is
 * {@link Ordered#LOWEST_PRECEDENCE}), low enough that infrastructure
 * advices (security / observability) can still wrap. Without an
 * explicit order, the lowest-precedence shared advice would also
 * match these exceptions and produce a less-specific response.
 *
 * <p>Mappings:
 *
 * <ul>
 *   <li>{@link AccountInFreezePeriodException} → 403
 *       {@code ACCOUNT_IN_FREEZE_PERIOD} (FROZEN account login attempt;
 *       explicit disclosure per spec D expose-frozen-account-status,
 *       with extended {@code freezeUntil} ISO 8601 UTC field)
 *   <li>{@link InvalidCredentialsException} → 401
 *       {@code INVALID_CREDENTIALS} (FR-007 anti-enumeration uniform
 *       response — wrong code, expired code, already-registered, etc.)
 *   <li>{@link InvalidPhoneFormatException} → 422
 *       {@code INVALID_PHONE_FORMAT}
 *   <li>{@link WeakPasswordException} → 422 {@code INVALID_PASSWORD}
 *   <li>{@link RateLimitedException} → 429 with {@code Retry-After}
 *       header
 *   <li>{@link SmsSendException} → 503 {@code SMS_SEND_FAILED}
 *       (FR-009)
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class AccountWebExceptionAdvice {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail onInvalidCredentials(InvalidCredentialsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        problem.setTitle("Invalid credentials");
        problem.setProperty("code", InvalidCredentialsException.CODE);
        return problem;
    }

    @ExceptionHandler(InvalidDeletionCodeException.class)
    public ProblemDetail onInvalidDeletionCode(InvalidDeletionCodeException ex) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Deletion code invalid or expired");
        problem.setTitle("Invalid deletion code");
        problem.setProperty("code", InvalidDeletionCodeException.CODE);
        return problem;
    }

    @ExceptionHandler(InvalidPhoneFormatException.class)
    public ProblemDetail onInvalidPhoneFormat(InvalidPhoneFormatException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, "Phone format does not satisfy FR-001");
        problem.setTitle("Invalid phone format");
        problem.setProperty("code", InvalidPhoneFormatException.CODE);
        return problem;
    }

    @ExceptionHandler(WeakPasswordException.class)
    public ProblemDetail onWeakPassword(WeakPasswordException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, "Password does not meet FR-003 strength requirements");
        problem.setTitle("Invalid password");
        problem.setProperty("code", WeakPasswordException.CODE);
        return problem;
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ProblemDetail> onRateLimited(RateLimitedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded; please retry after the indicated interval");
        problem.setTitle("Rate limited");
        problem.setProperty("code", "RATE_LIMITED");

        HttpHeaders headers = new HttpHeaders();
        long retryAfterSeconds = Math.max(0L, ex.getRetryAfter().toSeconds());
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(headers)
                .body(problem);
    }

    @ExceptionHandler(SmsSendException.class)
    public ProblemDetail onSmsSendFailure(SmsSendException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "SMS gateway temporarily unavailable; please retry");
        problem.setTitle("SMS send failed");
        problem.setProperty("code", "SMS_SEND_FAILED");
        return problem;
    }

    @ExceptionHandler(AccountInFreezePeriodException.class)
    public ProblemDetail onAccountInFreezePeriod(AccountInFreezePeriodException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Account in freeze period; cancel deletion to re-activate");
        problem.setTitle("Account in freeze period");
        problem.setProperty("code", AccountInFreezePeriodException.CODE);
        problem.setProperty("freezeUntil", ex.getFreezeUntil().toString());
        return problem;
    }

    /**
     * Anti-enumeration uniform 401 (account-profile FR-002 / FR-009).
     * The four paths — missing token / invalid-or-expired token /
     * unknown account id / account.status != ACTIVE — produce the
     * exact same {@link ProblemDetail} bytes so a caller cannot tell
     * which arm fired. Verified end-to-end by
     * {@code JwtAuthFailureUniformnessIT} (T8).
     */
    @ExceptionHandler({
        MissingAuthenticationException.class,
        AccountNotFoundException.class,
        AccountInactiveException.class
    })
    public ProblemDetail onAuthFailure(RuntimeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication failed");
        problem.setTitle("Authentication failed");
        problem.setProperty("code", "AUTH_FAILED");
        return problem;
    }

    @ExceptionHandler(DeviceNotFoundException.class)
    public ProblemDetail onDeviceNotFound(DeviceNotFoundException ex) {
        // Device-management spec FR-014: anti-enumeration — same body for missing
        // recordId and cross-account recordId.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Device session not found");
        problem.setTitle("Device not found");
        problem.setProperty("code", "DEVICE_NOT_FOUND");
        return problem;
    }

    @ExceptionHandler(CannotRemoveCurrentDeviceException.class)
    public ProblemDetail onCannotRemoveCurrent(CannotRemoveCurrentDeviceException ex) {
        // Device-management spec FR-005: server rejects self-revoke; client should
        // route the user to logout-all instead.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "当前设备请通过『退出登录』移除");
        problem.setTitle("Cannot remove current device");
        problem.setProperty("code", "CANNOT_REMOVE_CURRENT_DEVICE");
        return problem;
    }

    @ExceptionHandler(AgreementRequiredException.class)
    public ProblemDetail onAgreementRequired(AgreementRequiredException ex) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Realname agreement must be accepted");
        problem.setTitle("Agreement required");
        problem.setProperty("code", AgreementRequiredException.CODE);
        return problem;
    }

    @ExceptionHandler(InvalidIdCardFormatException.class)
    public ProblemDetail onInvalidIdCardFormat(InvalidIdCardFormatException ex) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "ID card number does not satisfy GB 11643");
        problem.setTitle("Invalid ID card format");
        problem.setProperty("code", InvalidIdCardFormatException.CODE);
        return problem;
    }

    @ExceptionHandler(AlreadyVerifiedException.class)
    public ProblemDetail onAlreadyVerified(AlreadyVerifiedException ex) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Account is already realname-verified");
        problem.setTitle("Already verified");
        problem.setProperty("code", AlreadyVerifiedException.CODE);
        return problem;
    }

    @ExceptionHandler(IdCardOccupiedException.class)
    public ProblemDetail onIdCardOccupied(IdCardOccupiedException ex) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "ID card is already bound to another account");
        problem.setTitle("ID card occupied");
        problem.setProperty("code", IdCardOccupiedException.CODE);
        return problem;
    }

    @ExceptionHandler(ProviderTimeoutException.class)
    public ProblemDetail onProviderTimeout(ProviderTimeoutException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Realname provider timed out; please retry");
        problem.setTitle("Provider timeout");
        problem.setProperty("code", ProviderTimeoutException.CODE);
        return problem;
    }

    @ExceptionHandler(ProviderErrorException.class)
    public ProblemDetail onProviderError(ProviderErrorException ex) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Realname provider returned an error");
        problem.setTitle("Provider error");
        problem.setProperty("code", ProviderErrorException.CODE);
        return problem;
    }

    @ExceptionHandler(RealnameProfileNotFoundException.class)
    public ProblemDetail onRealnameProfileNotFound(RealnameProfileNotFoundException ex) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Realname verification record not found");
        problem.setTitle("Realname profile not found");
        problem.setProperty("code", RealnameProfileNotFoundException.CODE);
        return problem;
    }

    @ExceptionHandler(RealnameProfileAccessDeniedException.class)
    public ProblemDetail onRealnameProfileAccessDenied(RealnameProfileAccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Caller does not own the requested realname verification record");
        problem.setTitle("Realname access denied");
        problem.setProperty("code", RealnameProfileAccessDeniedException.CODE);
        return problem;
    }
}
