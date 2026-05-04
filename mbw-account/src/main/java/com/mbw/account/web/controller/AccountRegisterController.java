package com.mbw.account.web.controller;

import com.mbw.account.application.command.RequestSmsCodeCommand;
import com.mbw.account.application.usecase.RequestSmsCodeUseCase;
import com.mbw.account.web.request.RequestSmsCodeRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for the SMS-code request endpoint (per ADR-0016
 * unified mobile-first phone-SMS auth + spec
 * {@code phone-sms-auth/spec.md} FR-004).
 *
 * <p>Endpoint:
 *
 * <ul>
 *   <li>{@code POST /api/v1/sms-codes} — request a verification code;
 *       returns 200 on the byte-identical path regardless of phone
 *       existence (anti-enumeration; server sends single Template A)
 * </ul>
 *
 * <p>The legacy {@code POST /api/v1/accounts/register-by-phone} has
 * been removed (per ADR-0016 决策 2). The unified auth flow at
 * {@code POST /api/v1/accounts/phone-sms-auth} handles both register
 * and login internally — see {@link AccountAuthController}.
 *
 * <p>Class naming preserved (vs renaming to {@code AccountSmsCodeController})
 * to keep package history clean — the class no longer has any "register"
 * semantics, only the SMS-code request side of unified auth.
 */
@RestController
@RequestMapping("/api/v1")
public class AccountRegisterController {

    private final RequestSmsCodeUseCase requestSmsCodeUseCase;

    public AccountRegisterController(RequestSmsCodeUseCase requestSmsCodeUseCase) {
        this.requestSmsCodeUseCase = requestSmsCodeUseCase;
    }

    @PostMapping("/sms-codes")
    public ResponseEntity<Void> requestSmsCode(
            @Valid @RequestBody RequestSmsCodeRequest body, HttpServletRequest request) {
        requestSmsCodeUseCase.execute(new RequestSmsCodeCommand(body.phone(), clientIp(request)));
        return ResponseEntity.status(HttpStatus.OK).build();
    }

    /**
     * Resolve client IP for FR-007 IP-tier rate-limiting. Honours the
     * first {@code X-Forwarded-For} entry when behind Nginx
     * (deployment per ADR-0002); otherwise falls back to remote addr.
     * Anti-spoofing of {@code X-Forwarded-For} is the reverse proxy's
     * responsibility — Nginx strips client-supplied values before
     * setting its own (configured at deployment).
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
