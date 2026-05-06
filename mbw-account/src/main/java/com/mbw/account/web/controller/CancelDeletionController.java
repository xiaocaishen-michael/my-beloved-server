package com.mbw.account.web.controller;

import com.mbw.account.application.command.CancelDeletionCommand;
import com.mbw.account.application.command.SendCancelDeletionCodeCommand;
import com.mbw.account.application.result.CancelDeletionResult;
import com.mbw.account.application.usecase.CancelDeletionUseCase;
import com.mbw.account.application.usecase.SendCancelDeletionCodeUseCase;
import com.mbw.account.web.request.CancelDeletionRequest;
import com.mbw.account.web.request.SendCancelDeletionCodeRequest;
import com.mbw.account.web.response.LoginResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for the cancel-deletion use case (per
 * {@code spec/account/cancel-deletion/spec.md}).
 *
 * <p>Both endpoints are <strong>public, unauthed</strong> — clients in
 * FROZEN state have no valid tokens. Anti-enumeration responses
 * (FR-006 / SC-002) are produced by the underlying use cases; this
 * layer only handles request validation, body shape, and IP extraction.
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code POST /api/v1/auth/cancel-deletion/sms-codes} — send a
 *       CANCEL_DELETION SMS code; 4 ineligible phone classes silently
 *       return 200 (no SMS dispatched).
 *   <li>{@code POST /api/v1/auth/cancel-deletion} — submit code,
 *       transition FROZEN → ACTIVE, and receive a fresh access /
 *       refresh token pair.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth/cancel-deletion")
public class CancelDeletionController {

    private final SendCancelDeletionCodeUseCase sendCancelDeletionCodeUseCase;
    private final CancelDeletionUseCase cancelDeletionUseCase;

    public CancelDeletionController(
            SendCancelDeletionCodeUseCase sendCancelDeletionCodeUseCase, CancelDeletionUseCase cancelDeletionUseCase) {
        this.sendCancelDeletionCodeUseCase = sendCancelDeletionCodeUseCase;
        this.cancelDeletionUseCase = cancelDeletionUseCase;
    }

    @PostMapping("/sms-codes")
    public ResponseEntity<Void> sendCode(
            @Valid @RequestBody SendCancelDeletionCodeRequest body, HttpServletRequest request) {
        sendCancelDeletionCodeUseCase.execute(new SendCancelDeletionCodeCommand(body.phone(), clientIp(request)));
        return ResponseEntity.ok().build();
    }

    @PostMapping
    public ResponseEntity<LoginResponse> cancel(
            @Valid @RequestBody CancelDeletionRequest body, HttpServletRequest request) {
        CancelDeletionResult result =
                cancelDeletionUseCase.execute(new CancelDeletionCommand(body.phone(), body.code(), clientIp(request)));
        return ResponseEntity.ok(new LoginResponse(result.accountId(), result.accessToken(), result.refreshToken()));
    }

    /**
     * Resolve client IP for IP-tier rate-limiting. Honours the first
     * {@code X-Forwarded-For} entry when behind Nginx (per ADR-0002);
     * falls back to remote addr. Anti-spoofing of XFF is the reverse
     * proxy's responsibility.
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
