package com.mbw.account.web.security;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.web.exception.MissingAuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@code @AuthenticatedAccountId AccountId} controller
 * parameters from the request attribute populated by
 * {@code JwtAuthFilter} (key {@code mbw.accountId}). When the attribute
 * is absent, throws {@link MissingAuthenticationException}, which the
 * account web advice maps to a byte-equal 401 ProblemDetail
 * (account-profile FR-002 / FR-009).
 *
 * <p>Registered via {@code AccountWebMvcConfig} (T6).
 */
@Component
public class AuthenticatedAccountIdResolver implements HandlerMethodArgumentResolver {

    public static final String ACCOUNT_ID_ATTRIBUTE = "mbw.accountId";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(AuthenticatedAccountId.class)
                && AccountId.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw new MissingAuthenticationException();
        }
        Object attr = request.getAttribute(ACCOUNT_ID_ATTRIBUTE);
        if (attr instanceof AccountId accountId) {
            return accountId;
        }
        throw new MissingAuthenticationException();
    }
}
