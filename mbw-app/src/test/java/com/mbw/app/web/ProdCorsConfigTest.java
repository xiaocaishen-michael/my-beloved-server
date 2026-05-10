package com.mbw.app.web;

import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

class ProdCorsConfigTest {

    @Test
    void addCorsMappings_registers_api_path_with_pages_dev_and_custom_domain_origin_patterns() {
        CorsRegistry registry = mock(CorsRegistry.class);
        CorsRegistration registration = mock(CorsRegistration.class, RETURNS_SELF);
        when(registry.addMapping("/api/**")).thenReturn(registration);

        new ProdCorsConfig().addCorsMappings(registry);

        verify(registry).addMapping("/api/**");
        verify(registration)
                .allowedOriginPatterns(
                        "https://app.xiaocaishen.me",
                        "https://no-vain-years-app.pages.dev",
                        "https://*.no-vain-years-app.pages.dev");
        verify(registration).allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        verify(registration).allowedHeaders("*");
        verify(registration).allowCredentials(false);
        verify(registration).maxAge(3600L);
    }
}
