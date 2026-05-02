package com.mbw.account.web.controller;

import com.mbw.account.application.command.LoginByPhoneSmsCommand;
import com.mbw.account.application.result.LoginByPhoneSmsResult;
import com.mbw.account.application.usecase.LoginByPhoneSmsUseCase;
import com.mbw.account.web.request.LoginByPhoneSmsRequest;
import com.mbw.account.web.response.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry points for authentication use cases (login-by-phone-sms in
 * Phase 1.1; login-by-password / refresh-token / logout-all to follow
 * in Phases 1.2 / 1.3 / 1.4 — same controller, parallel methods).
 *
 * <p>Spec mapping: {@code POST /api/v1/auth/login-by-phone-sms} per
 * {@code spec/account/login-by-phone-sms/spec.md} FR-002.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final LoginByPhoneSmsUseCase loginByPhoneSmsUseCase;

    public AuthController(LoginByPhoneSmsUseCase loginByPhoneSmsUseCase) {
        this.loginByPhoneSmsUseCase = loginByPhoneSmsUseCase;
    }

    @PostMapping("/login-by-phone-sms")
    public ResponseEntity<LoginResponse> loginByPhoneSms(@Valid @RequestBody LoginByPhoneSmsRequest body) {
        LoginByPhoneSmsResult result =
                loginByPhoneSmsUseCase.execute(new LoginByPhoneSmsCommand(body.phone(), body.code()));
        return ResponseEntity.ok(new LoginResponse(result.accountId(), result.accessToken(), result.refreshToken()));
    }
}
