package com.mbw.app.infrastructure.security;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.service.AuthenticatedTokenClaims;
import com.mbw.account.domain.service.TokenIssuer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that decodes a {@code Authorization: Bearer <jwt>}
 * header and stashes the authenticated identity on the request:
 *
 * <ul>
 *   <li>{@value #ACCOUNT_ID_ATTRIBUTE} — always set when the token's
 *       signature and {@code sub} claim are valid. Endpoints that don't
 *       need device awareness (logout-all, account-profile, deletion)
 *       read this attribute directly.
 *   <li>{@value #DEVICE_ID_ATTRIBUTE} — set only when the token also
 *       carries a {@code did} claim (device-management spec FR-006 /
 *       FR-008). Old-format tokens issued before the device-management
 *       upgrade omit it; device-management endpoints check for this
 *       attribute and reject the request with 401 when missing,
 *       forcing a re-login that produces a token with did.
 * </ul>
 *
 * <p>Two-step verification keeps the legacy code path (logout-all uses
 * {@code verifyAccess} for tokens without did) working through the
 * rollout window without a flag day.
 *
 * <p>Per spec/account/account-profile/plan.md § JWT 前置基础设施 the
 * filter does not consult the database — it only verifies the
 * signature + parses claims. {@code Account.status != ACTIVE} (FR-009)
 * is handled at the application layer so the 401 anti-enumeration path
 * stays uniform whether the token is bad or the account is inactive.
 *
 * <p>{@link OncePerRequestFilter} guarantees a single execution per
 * request even if the dispatcher forwards. Spring Boot auto-registers
 * any {@code Filter} bean to the chain (default order = highest among
 * user-registered filters).
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String ACCOUNT_ID_ATTRIBUTE = "mbw.accountId";
    public static final String DEVICE_ID_ATTRIBUTE = "mbw.deviceId";
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenIssuer tokenIssuer;

    public JwtAuthFilter(TokenIssuer tokenIssuer) {
        this.tokenIssuer = tokenIssuer;
    }

    @Override
    @SuppressWarnings("deprecation") // verifyAccess kept for legacy tokens during rollout
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            // Prefer device-aware verification; fall back to legacy verify for
            // pre-upgrade tokens missing the did claim.
            tokenIssuer
                    .verifyAccessWithDevice(token)
                    .ifPresentOrElse(
                            (AuthenticatedTokenClaims claims) -> {
                                request.setAttribute(ACCOUNT_ID_ATTRIBUTE, claims.accountId());
                                request.setAttribute(DEVICE_ID_ATTRIBUTE, claims.deviceId());
                            },
                            () -> tokenIssuer
                                    .verifyAccess(token)
                                    .ifPresent((AccountId id) -> request.setAttribute(ACCOUNT_ID_ATTRIBUTE, id)));
        }
        chain.doFilter(request, response);
    }
}
