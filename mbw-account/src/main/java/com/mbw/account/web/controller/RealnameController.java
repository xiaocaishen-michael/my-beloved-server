package com.mbw.account.web.controller;

import com.mbw.account.application.command.ConfirmRealnameCommand;
import com.mbw.account.application.command.InitiateRealnameCommand;
import com.mbw.account.application.usecase.ConfirmRealnameVerificationUseCase;
import com.mbw.account.application.usecase.InitiateRealnameVerificationUseCase;
import com.mbw.account.application.usecase.QueryRealnameStatusUseCase;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.web.exception.MissingAuthenticationException;
import com.mbw.account.web.request.RealnameInitiateRequest;
import com.mbw.account.web.response.RealnameConfirmResponse;
import com.mbw.account.web.response.RealnameInitiateResponse;
import com.mbw.account.web.response.RealnameStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for realname verification (realname-verification spec T16,
 * plan.md § Web).
 *
 * <p>Endpoints (all require Bearer JWT; missing/invalid → uniform 401 via
 * {@link MissingAuthenticationException}):
 *
 * <ul>
 *   <li>{@code GET /api/v1/realname/me} — current account's realname status.
 *   <li>{@code POST /api/v1/realname/verifications} — start a new verification
 *       attempt; returns {@code providerBizId} + {@code livenessUrl}.
 *   <li>{@code GET /api/v1/realname/verifications/{providerBizId}} — poll the
 *       verification result; idempotent on terminal states.
 * </ul>
 *
 * <p>No DELETE / PATCH (FR-015 不可解绑); Spring routing returns 405 for those.
 */
@RestController
@RequestMapping("/api/v1/realname")
public class RealnameController {

    private final QueryRealnameStatusUseCase queryUseCase;
    private final InitiateRealnameVerificationUseCase initiateUseCase;
    private final ConfirmRealnameVerificationUseCase confirmUseCase;

    public RealnameController(
            QueryRealnameStatusUseCase queryUseCase,
            InitiateRealnameVerificationUseCase initiateUseCase,
            ConfirmRealnameVerificationUseCase confirmUseCase) {
        this.queryUseCase = queryUseCase;
        this.initiateUseCase = initiateUseCase;
        this.confirmUseCase = confirmUseCase;
    }

    @GetMapping("/me")
    public ResponseEntity<RealnameStatusResponse> getMyStatus(HttpServletRequest request) {
        AccountId accountId = authenticatedAccountId(request);
        return ResponseEntity.ok(RealnameStatusResponse.from(queryUseCase.execute(accountId.value())));
    }

    @PostMapping("/verifications")
    public ResponseEntity<RealnameInitiateResponse> initiate(
            HttpServletRequest request, @Valid @RequestBody RealnameInitiateRequest body) {
        AccountId accountId = authenticatedAccountId(request);
        InitiateRealnameCommand cmd = new InitiateRealnameCommand(
                accountId, body.realName(), body.idCardNo(), body.agreementVersion(), clientIp(request));
        return ResponseEntity.ok(RealnameInitiateResponse.from(initiateUseCase.execute(cmd)));
    }

    @GetMapping("/verifications/{providerBizId}")
    public ResponseEntity<RealnameConfirmResponse> confirm(
            HttpServletRequest request, @PathVariable String providerBizId) {
        AccountId accountId = authenticatedAccountId(request);
        return ResponseEntity.ok(RealnameConfirmResponse.from(
                confirmUseCase.execute(new ConfirmRealnameCommand(accountId, providerBizId))));
    }

    private static AccountId authenticatedAccountId(HttpServletRequest request) {
        Object attr = request.getAttribute("mbw.accountId");
        if (attr instanceof AccountId accountId) {
            return accountId;
        }
        throw new MissingAuthenticationException();
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
