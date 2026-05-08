package com.mbw.account.web.controller;

import com.mbw.account.application.command.PhoneSmsAuthCommand;
import com.mbw.account.application.result.PhoneSmsAuthResult;
import com.mbw.account.application.usecase.UnifiedPhoneSmsAuthUseCase;
import com.mbw.account.web.request.PhoneSmsAuthRequest;
import com.mbw.account.web.resolver.DeviceMetadata;
import com.mbw.account.web.resolver.DeviceMetadataExtractor;
import com.mbw.account.web.response.LoginResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for the unified phone-SMS auth use case (per
 * ADR-0016 + spec {@code phone-sms-auth/spec.md}).
 *
 * <p>Endpoint:
 *
 * <ul>
 *   <li>{@code POST /api/v1/accounts/phone-sms-auth} — submit phone +
 *       6-digit SMS code; server auto-branches on phone existence
 *       (register if not exists, login if ACTIVE, anti-enumeration
 *       error if FROZEN/ANONYMIZED). Returns access + refresh tokens.
 * </ul>
 *
 * <p>Replaces three legacy endpoints (per ADR-0016 决策 2 一刀切):
 *
 * <ul>
 *   <li>{@code POST /api/v1/accounts/register-by-phone} — removed
 *   <li>{@code POST /api/v1/auth/login-by-phone-sms} — removed
 *   <li>{@code POST /api/v1/auth/login-by-password} — removed
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountAuthController {

    private final UnifiedPhoneSmsAuthUseCase phoneSmsAuthUseCase;

    public AccountAuthController(UnifiedPhoneSmsAuthUseCase phoneSmsAuthUseCase) {
        this.phoneSmsAuthUseCase = phoneSmsAuthUseCase;
    }

    @PostMapping("/phone-sms-auth")
    public ResponseEntity<LoginResponse> phoneSmsAuth(
            @Valid @RequestBody PhoneSmsAuthRequest body, HttpServletRequest request) {
        DeviceMetadata deviceMetadata = DeviceMetadataExtractor.extractDeviceMetadata(request);
        PhoneSmsAuthResult result = phoneSmsAuthUseCase.execute(
                new PhoneSmsAuthCommand(body.phone(), body.code(), clientIp(request), deviceMetadata));
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
