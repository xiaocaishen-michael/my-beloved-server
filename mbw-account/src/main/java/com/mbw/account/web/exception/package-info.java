/** Account-module exception → HTTP ProblemDetail mapping. Runs at
 *  {@code HIGHEST_PRECEDENCE + 100} (T16) so module-specific
 *  exceptions are caught here before the catch-all advice in
 *  {@code mbw-shared.web.GlobalExceptionHandler}. */
package com.mbw.account.web.exception;
