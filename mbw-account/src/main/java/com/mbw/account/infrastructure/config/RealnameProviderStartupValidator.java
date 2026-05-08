package com.mbw.account.infrastructure.config;

import java.util.Arrays;
import org.springframework.core.env.Environment;

/**
 * Boot-time guard for the realname-verification provider stack
 * (realname-verification spec T11). Constructor throws {@link IllegalStateException}
 * when the {@code prod} profile is active but any of the following holds:
 *
 * <ul>
 *   <li>{@code mbw.realname.dev-bypass=true} — forbidden in prod since it
 *       would short-circuit upstream Aliyun verification.
 *   <li>{@code mbw.realname.dek.base64} — missing / blank.
 *   <li>{@code mbw.realname.aliyun.access-key-id} — missing / blank.
 *   <li>{@code mbw.realname.aliyun.access-key-secret} — missing / blank.
 *   <li>{@code mbw.realname.aliyun.scene-id} — missing / blank.
 * </ul>
 *
 * <p>Non-prod profiles skip all checks: dev / staging are free to
 * opt into bypass mode or omit Aliyun credentials.
 */
class RealnameProviderStartupValidator {

    private static final String PROD_PROFILE = "prod";

    RealnameProviderStartupValidator(Environment env) {
        if (!isProdProfile(env)) {
            return;
        }
        if ("true".equalsIgnoreCase(env.getProperty("mbw.realname.dev-bypass"))) {
            throw new IllegalStateException(
                    "mbw.realname.dev-bypass=true is forbidden in prod profile (would short-circuit upstream verification)");
        }
        requireProperty(env, "mbw.realname.dek.base64");
        requireProperty(env, "mbw.realname.aliyun.access-key-id");
        requireProperty(env, "mbw.realname.aliyun.access-key-secret");
        requireProperty(env, "mbw.realname.aliyun.scene-id");
    }

    private static boolean isProdProfile(Environment env) {
        return Arrays.asList(env.getActiveProfiles()).contains(PROD_PROFILE);
    }

    private static void requireProperty(Environment env, String name) {
        String value = env.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required in prod profile but is missing or blank");
        }
    }
}
