package com.mbw.account.web.controller;

import com.mbw.account.application.command.LogoutAllSessionsCommand;
import com.mbw.account.application.result.RefreshTokenResult;
import com.mbw.account.application.usecase.LogoutAllSessionsUseCase;
import com.mbw.account.application.usecase.RefreshTokenUseCase;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.service.TokenIssuer;
import com.mbw.account.web.request.RefreshTokenRequest;
import com.mbw.account.web.response.LoginResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry points for token-lifecycle auth use cases (refresh-token
 * + logout-all). The login entry points have moved to
 * {@code AccountAuthController} under {@code /api/v1/accounts/phone-sms-auth}
 * (per ADR-0016 unified mobile-first phone-SMS auth).
 *
 * <p>Spec mapping:
 *
 * <ul>
 *   <li>{@code POST /api/v1/auth/refresh-token} —
 *       {@code spec/account/refresh-token/spec.md}
 *   <li>{@code POST /api/v1/auth/logout-all} —
 *       {@code spec/account/logout-all/spec.md}
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RefreshTokenUseCase refreshTokenUseCase;
    private final LogoutAllSessionsUseCase logoutAllSessionsUseCase;
    private final TokenIssuer tokenIssuer;

    public AuthController(
            RefreshTokenUseCase refreshTokenUseCase,
            LogoutAllSessionsUseCase logoutAllSessionsUseCase,
            TokenIssuer tokenIssuer) {
        this.refreshTokenUseCase = refreshTokenUseCase;
        this.logoutAllSessionsUseCase = logoutAllSessionsUseCase;
        this.tokenIssuer = tokenIssuer;
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<LoginResponse> refreshToken(
            @Valid @RequestBody RefreshTokenRequest body, HttpServletRequest request) {
        RefreshTokenResult result = refreshTokenUseCase.execute(body.toCommand(clientIp(request)));
        return ResponseEntity.ok(new LoginResponse(result.accountId(), result.accessToken(), result.refreshToken()));
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(HttpServletRequest request) {
        // Manual JWT verification — there is no Spring Security filter
        // chain in M1.2 (per server pom: only spring-security-crypto for
        // BCrypt + nimbus-jose-jwt). When a real filter chain ships in
        // M3+, this controller-side check moves up into the filter and
        // logout-all simply consumes @AuthenticationPrincipal.
        AccountId accountId = extractAccountIdOrThrow(request);
        logoutAllSessionsUseCase.execute(new LogoutAllSessionsCommand(accountId, clientIp(request)));
        return ResponseEntity.noContent().build();
    }

    private AccountId extractAccountIdOrThrow(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new InvalidCredentialsException();
        }
        String token = authorization.substring("Bearer ".length()).trim();
        Optional<AccountId> verified = tokenIssuer.verifyAccess(token);
        return verified.orElseThrow(InvalidCredentialsException::new);
    }

    /**
     * Resolve client IP for FR-007 IP-tier rate-limiting. Honours the
     * first {@code X-Forwarded-For} entry when behind Nginx (deployment
     * per ADR-0002); otherwise falls back to remote addr. Anti-spoofing
     * of {@code X-Forwarded-For} is the reverse proxy's responsibility.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
