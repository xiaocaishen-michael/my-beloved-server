package com.mbw.account.web.exception;

import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.domain.exception.InvalidPhoneFormatException;
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
}
