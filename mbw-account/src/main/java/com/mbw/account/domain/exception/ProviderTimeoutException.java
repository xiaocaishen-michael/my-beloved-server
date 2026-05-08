package com.mbw.account.domain.exception;

/**
 * Domain exception wrapping an upstream Aliyun realname-verification timeout
 * or 5xx — used both for the initiate path (transactional rollback) and the
 * confirm path (status stays PENDING, client can retry). Web advice maps to
 * HTTP 503 with error code {@link #CODE}.
 *
 * <p>The wrapped {@link Throwable#getCause()} carries the SDK-level error
 * for trace / log diagnostics; never propagate the cause's message to
 * clients (it may carry internal service identifiers).
 */
public class ProviderTimeoutException extends RuntimeException {

    public static final String CODE = "REALNAME_PROVIDER_TIMEOUT";

    public ProviderTimeoutException(Throwable cause) {
        super(CODE, cause);
    }

    public ProviderTimeoutException(String detail, Throwable cause) {
        super(CODE + ": " + detail, cause);
    }
}
