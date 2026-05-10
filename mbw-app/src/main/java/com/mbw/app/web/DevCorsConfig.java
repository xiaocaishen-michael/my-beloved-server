package com.mbw.app.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Dev-only CORS allow-list for the local Expo Web client running on
 * {@code http://localhost:8081}. Production CORS is handled by
 * {@link ProdCorsConfig} (cross-origin: Cloudflare Pages
 * {@code app.xiaocaishen.me} → API {@code api.xiaocaishen.me}); this
 * configuration is gated behind the {@code dev} profile.
 *
 * <p>Allows {@code Authorization} header so the client can attach
 * Bearer JWTs after login. {@code allowCredentials=false} because the
 * auth model is header-bearer not cookie-session.
 */
@Configuration
@Profile("dev")
public class DevCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:8081")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
