package com.mbw.account.web.security;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link AuthenticatedAccountIdResolver} so the
 * {@code @AuthenticatedAccountId} annotation works on controller method
 * parameters.
 */
@Configuration
public class AccountWebMvcConfig implements WebMvcConfigurer {

    private final AuthenticatedAccountIdResolver resolver;

    public AccountWebMvcConfig(AuthenticatedAccountIdResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(resolver);
    }
}
