package com.mbw.app.infrastructure.security;

import com.mbw.account.domain.model.AccountId;
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
 * header into an {@link AccountId} and stashes it as the
 * {@value #ACCOUNT_ID_ATTRIBUTE} request attribute. Downstream controller
 * argument resolvers (see {@code @AuthenticatedAccountId}, T6) read it
 * back; missing / invalid / expired tokens leave the attribute unset so
 * the resolver throws {@code MissingAuthenticationException} → 401.
 *
 * <p>Per spec/account/account-profile/plan.md § JWT 前置基础设施 the
 * filter does not consult the database — it only verifies the signature
 * + parses {@code sub}. {@code Account.status != ACTIVE} (FR-009) is
 * handled at the application layer so the 401 anti-enumeration path
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
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenIssuer tokenIssuer;

    public JwtAuthFilter(TokenIssuer tokenIssuer) {
        this.tokenIssuer = tokenIssuer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            tokenIssuer.verifyAccess(token).ifPresent(id -> request.setAttribute(ACCOUNT_ID_ATTRIBUTE, id));
        }
        chain.doFilter(request, response);
    }
}
