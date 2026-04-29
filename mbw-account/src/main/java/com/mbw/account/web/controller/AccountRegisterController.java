package com.mbw.account.web.controller;

import com.mbw.account.application.command.RegisterByPhoneCommand;
import com.mbw.account.application.command.RequestSmsCodeCommand;
import com.mbw.account.application.result.RegisterByPhoneResult;
import com.mbw.account.application.usecase.RegisterByPhoneUseCase;
import com.mbw.account.application.usecase.RequestSmsCodeUseCase;
import com.mbw.account.web.request.RegisterByPhoneRequest;
import com.mbw.account.web.request.RequestSmsCodeRequest;
import com.mbw.account.web.response.RegisterByPhoneResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry points for the register-by-phone use case (T15).
 *
 * <p>Two endpoints, both per spec.md:
 *
 * <ul>
 *   <li>{@code POST /api/v1/sms-codes} — request a one-time code; 200
 *       on the FR-012 byte-identical path (registered or not)
 *   <li>{@code POST /api/v1/accounts/register-by-phone} — submit phone
 *       + code (+ optional password), returns access + refresh tokens
 * </ul>
 *
 * <p>The controller is a thin HTTP adapter — it converts request DTOs
 * into application-layer Commands, calls the use case, and maps the
 * result. Domain / framework exceptions are mapped by
 * {@code AccountWebExceptionAdvice} (T16).
 */
@RestController
@RequestMapping("/api/v1")
public class AccountRegisterController {

    private final RequestSmsCodeUseCase requestSmsCodeUseCase;
    private final RegisterByPhoneUseCase registerByPhoneUseCase;

    public AccountRegisterController(
            RequestSmsCodeUseCase requestSmsCodeUseCase, RegisterByPhoneUseCase registerByPhoneUseCase) {
        this.requestSmsCodeUseCase = requestSmsCodeUseCase;
        this.registerByPhoneUseCase = registerByPhoneUseCase;
    }

    @PostMapping("/sms-codes")
    public ResponseEntity<Void> requestSmsCode(
            @Valid @RequestBody RequestSmsCodeRequest body, HttpServletRequest request) {
        requestSmsCodeUseCase.execute(new RequestSmsCodeCommand(body.phone(), clientIp(request)));
        return ResponseEntity.status(HttpStatus.OK).build();
    }

    @PostMapping("/accounts/register-by-phone")
    public ResponseEntity<RegisterByPhoneResponse> registerByPhone(@Valid @RequestBody RegisterByPhoneRequest body) {
        RegisterByPhoneResult result = registerByPhoneUseCase.execute(
                new RegisterByPhoneCommand(body.phone(), body.code(), Optional.ofNullable(body.password())));
        return ResponseEntity.ok(
                new RegisterByPhoneResponse(result.accountId(), result.accessToken(), result.refreshToken()));
    }

    /**
     * Resolve client IP for FR-006 IP-tier rate-limiting. Honours the
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
