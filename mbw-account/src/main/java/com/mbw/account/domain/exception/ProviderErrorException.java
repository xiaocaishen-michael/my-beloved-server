package com.mbw.account.domain.exception;

/**
 * Domain exception wrapping an upstream Aliyun realname-verification business
 * error (non-timeout, non-5xx; e.g. parameter rejected by upstream API).
 * Distinct from {@link ProviderTimeoutException} so the web layer can map to
 * HTTP 502 instead of 503.
 *
 * <p>The wrapped {@link Throwable#getCause()} carries the SDK-level error
 * for trace / log diagnostics; never propagate the cause's message to
 * clients.
 */
public class ProviderErrorException extends RuntimeException {

    public static final String CODE = "REALNAME_PROVIDER_ERROR";

    public ProviderErrorException(Throwable cause) {
        super(CODE, cause);
    }

    public ProviderErrorException(String detail) {
        super(CODE + ": " + detail);
    }

    public ProviderErrorException(String detail, Throwable cause) {
        super(CODE + ": " + detail, cause);
    }
}
