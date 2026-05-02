package com.mbw.account.web.controller;

import com.mbw.account.application.command.LoginByPhoneSmsCommand;
import com.mbw.account.application.result.LoginByPasswordResult;
import com.mbw.account.application.result.LoginByPhoneSmsResult;
import com.mbw.account.application.result.RefreshTokenResult;
import com.mbw.account.application.usecase.LoginByPasswordUseCase;
import com.mbw.account.application.usecase.LoginByPhoneSmsUseCase;
import com.mbw.account.application.usecase.RefreshTokenUseCase;
import com.mbw.account.web.request.LoginByPasswordRequest;
import com.mbw.account.web.request.LoginByPhoneSmsRequest;
import com.mbw.account.web.request.RefreshTokenRequest;
import com.mbw.account.web.response.LoginResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry points for authentication use cases (login-by-phone-sms in
 * Phase 1.1; login-by-password in Phase 1.2; refresh-token / logout-all
 * to follow in Phases 1.3 / 1.4 — same controller, parallel methods).
 *
 * <p>Spec mapping:
 *
 * <ul>
 *   <li>{@code POST /api/v1/auth/login-by-phone-sms} —
 *       {@code spec/account/login-by-phone-sms/spec.md} FR-002
 *   <li>{@code POST /api/v1/auth/login-by-password} —
 *       {@code spec/account/login-by-password/spec.md} FR-002
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final LoginByPhoneSmsUseCase loginByPhoneSmsUseCase;
    private final LoginByPasswordUseCase loginByPasswordUseCase;
    private final RefreshTokenUseCase refreshTokenUseCase;

    public AuthController(
            LoginByPhoneSmsUseCase loginByPhoneSmsUseCase,
            LoginByPasswordUseCase loginByPasswordUseCase,
            RefreshTokenUseCase refreshTokenUseCase) {
        this.loginByPhoneSmsUseCase = loginByPhoneSmsUseCase;
        this.loginByPasswordUseCase = loginByPasswordUseCase;
        this.refreshTokenUseCase = refreshTokenUseCase;
    }

    @PostMapping("/login-by-phone-sms")
    public ResponseEntity<LoginResponse> loginByPhoneSms(@Valid @RequestBody LoginByPhoneSmsRequest body) {
        LoginByPhoneSmsResult result =
                loginByPhoneSmsUseCase.execute(new LoginByPhoneSmsCommand(body.phone(), body.code()));
        return ResponseEntity.ok(new LoginResponse(result.accountId(), result.accessToken(), result.refreshToken()));
    }

    @PostMapping("/login-by-password")
    public ResponseEntity<LoginResponse> loginByPassword(
            @Valid @RequestBody LoginByPasswordRequest body, HttpServletRequest request) {
        LoginByPasswordResult result = loginByPasswordUseCase.execute(body.toCommand(clientIp(request)));
        return ResponseEntity.ok(new LoginResponse(result.accountId(), result.accessToken(), result.refreshToken()));
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<LoginResponse> refreshToken(
            @Valid @RequestBody RefreshTokenRequest body, HttpServletRequest request) {
        RefreshTokenResult result = refreshTokenUseCase.execute(body.toCommand(clientIp(request)));
        return ResponseEntity.ok(new LoginResponse(result.accountId(), result.accessToken(), result.refreshToken()));
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
