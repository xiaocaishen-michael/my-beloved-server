package com.mbw.app.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Production CORS allow-list for the Cloudflare Pages-hosted Web client.
 * Activated under the {@code prod} profile (set by {@code docker-compose.tight.yml}
 * via {@code SPRING_PROFILES_ACTIVE=prod}).
 *
 * <p>The Web bundle is served from {@code app.xiaocaishen.me} (custom domain
 * bound on Cloudflare Pages) while the API stays on {@code api.xiaocaishen.me}
 * — cross-origin by design. Preview deployments per PR live at
 * {@code <hash>.no-vain-years-app.pages.dev}, hence the wildcard pattern.
 *
 * <p>{@code allowedOriginPatterns} (not {@code allowedOrigins}) is required to
 * support the {@code *.pages.dev} wildcard. {@code allowCredentials=false}
 * matches the header-bearer auth model (no cookies).
 */
@Configuration
@Profile("prod")
public class ProdCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "https://app.xiaocaishen.me",
                        "https://no-vain-years-app.pages.dev",
                        "https://*.no-vain-years-app.pages.dev")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
