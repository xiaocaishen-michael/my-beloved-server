package com.mbw.account.web.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for controller method parameters that should be
 * resolved from the {@code mbw.accountId} request attribute set by
 * {@code JwtAuthFilter}. {@link AuthenticatedAccountIdResolver} resolves
 * the annotation; missing attribute → {@code MissingAuthenticationException}
 * → 401 ProblemDetail (account-profile FR-002 / FR-009 anti-enumeration).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthenticatedAccountId {}
