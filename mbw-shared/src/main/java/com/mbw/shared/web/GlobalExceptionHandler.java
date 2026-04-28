package com.mbw.shared.web;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global RFC 9457 ProblemDetail mapper.
 *
 * <p>Spring 6+ ships {@link ProblemDetail} natively; this advice routes
 * common exception types to it, ensuring all error responses use
 * {@code application/problem+json} content type with consistent fields:
 * {@code type / title / status / detail / instance}.
 *
 * <p>Business-specific exception classes (e.g. {@code QuotaExceededException},
 * {@code AccountFrozenException}) should be handled by their owning module's
 * own {@code @RestControllerAdvice} — this file is the cross-cutting fallback.
 *
 * <p>{@link Order} set to {@link Ordered#LOWEST_PRECEDENCE} so module-specific
 * advices win when both apply.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation failed");
        pd.setDetail(ex.getBindingResult().getAllErrors().stream()
                .map(e -> e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("invalid request"));
        return pd;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Constraint violation");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Bad request");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    /**
     * Rate-limit hit. Returns HTTP 429 with {@code Retry-After} header
     * (seconds, RFC 7231 §7.1.3 / RFC 6585 §4) so polite clients can
     * back off without guessing.
     */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(RateLimitedException ex) {
        long retryAfterSeconds = Math.max(1L, ex.getRetryAfter().toSeconds());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        pd.setTitle("Too many requests");
        pd.setDetail("Rate limit exceeded; retry after " + retryAfterSeconds + "s.");
        pd.setProperty("limitKey", ex.getLimitKey());
        pd.setProperty("retryAfterSeconds", retryAfterSeconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(pd);
    }

    @ExceptionHandler(Throwable.class)
    public ProblemDetail handleFallback(Throwable ex) {
        // Log full stack at ERROR; surface only generic message externally
        // to avoid leaking internals (stack traces, SQL fragments, etc.).
        LOG.error("Unhandled exception", ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal server error");
        pd.setDetail("An unexpected error occurred. Refer to X-Request-Id for support.");
        return pd;
    }
}
