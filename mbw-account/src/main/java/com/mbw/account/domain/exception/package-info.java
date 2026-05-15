/**
 * Account-module domain exceptions. Each carries a {@code CODE} constant
 * matching the public error contract in
 * {@code specs/auth/register-by-phone/spec.md}; the web layer maps
 * these to HTTP status codes via {@code AccountWebExceptionAdvice}
 * (T16). Domain exceptions are unchecked so service compositions stay
 * readable.
 */
package com.mbw.account.domain.exception;
