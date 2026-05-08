package com.mbw.account.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mbw.account.application.result.ConfirmRealnameResult;
import com.mbw.account.application.result.InitiateRealnameResult;
import com.mbw.account.application.result.RealnameStatusResult;
import com.mbw.account.application.usecase.ConfirmRealnameVerificationUseCase;
import com.mbw.account.application.usecase.InitiateRealnameVerificationUseCase;
import com.mbw.account.application.usecase.QueryRealnameStatusUseCase;
import com.mbw.account.domain.exception.AccountInFreezePeriodException;
import com.mbw.account.domain.exception.AgreementRequiredException;
import com.mbw.account.domain.exception.AlreadyVerifiedException;
import com.mbw.account.domain.exception.IdCardOccupiedException;
import com.mbw.account.domain.exception.InvalidIdCardFormatException;
import com.mbw.account.domain.exception.ProviderErrorException;
import com.mbw.account.domain.exception.ProviderTimeoutException;
import com.mbw.account.domain.exception.RealnameProfileAccessDeniedException;
import com.mbw.account.domain.exception.RealnameProfileNotFoundException;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.FailedReason;
import com.mbw.account.domain.model.RealnameStatus;
import com.mbw.account.web.exception.AccountWebExceptionAdvice;
import com.mbw.shared.web.RateLimitedException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RealnameControllerTest {

    private static final AccountId ACCOUNT_ID = new AccountId(7001L);
    private static final String VALID_BODY =
            "{\"realName\":\"张三\",\"idCardNo\":\"11010119900101004X\",\"agreementVersion\":\"v1\"}";

    @Mock
    private QueryRealnameStatusUseCase queryUseCase;

    @Mock
    private InitiateRealnameVerificationUseCase initiateUseCase;

    @Mock
    private ConfirmRealnameVerificationUseCase confirmUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RealnameController(queryUseCase, initiateUseCase, confirmUseCase))
                .setControllerAdvice(new AccountWebExceptionAdvice())
                .build();
    }

    @Test
    void GET_me_should_return_200_with_masked_fields_when_VERIFIED() throws Exception {
        when(queryUseCase.execute(ACCOUNT_ID.value()))
                .thenReturn(RealnameStatusResult.verified(
                        "*三", "1**************4X", Instant.parse("2026-04-01T10:00:00Z")));

        mockMvc.perform(get("/api/v1/realname/me").requestAttr("mbw.accountId", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.realNameMasked").value("*三"));
    }

    @Test
    void POST_verifications_should_return_200_with_providerBizId_and_livenessUrl_on_happy_path() throws Exception {
        when(initiateUseCase.execute(any()))
                .thenReturn(new InitiateRealnameResult("biz-uuid", "https://aliyun/face?token=x"));

        mockMvc.perform(post("/api/v1/realname/verifications")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerBizId").value("biz-uuid"))
                .andExpect(jsonPath("$.livenessUrl").value("https://aliyun/face?token=x"));
    }

    @Test
    void GET_verifications_byBizId_should_return_200_with_status_when_terminal() throws Exception {
        when(confirmUseCase.execute(any()))
                .thenReturn(new ConfirmRealnameResult(
                        "biz-uuid", RealnameStatus.VERIFIED, null, Instant.parse("2026-04-01T10:00:00Z")));

        mockMvc.perform(get("/api/v1/realname/verifications/biz-uuid").requestAttr("mbw.accountId", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.providerBizId").value("biz-uuid"));
    }

    @Test
    void GET_me_should_return_401_AUTH_FAILED_when_no_accountId_attribute() throws Exception {
        mockMvc.perform(get("/api/v1/realname/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
    }

    @Test
    void POST_verifications_should_return_400_INVALID_ID_CARD_FORMAT() throws Exception {
        when(initiateUseCase.execute(any())).thenThrow(new InvalidIdCardFormatException());

        mockMvc.perform(post("/api/v1/realname/verifications")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REALNAME_INVALID_ID_CARD_FORMAT"));
    }

    @Test
    void POST_verifications_should_return_400_AGREEMENT_REQUIRED() throws Exception {
        when(initiateUseCase.execute(any())).thenThrow(new AgreementRequiredException());

        mockMvc.perform(post("/api/v1/realname/verifications")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REALNAME_AGREEMENT_REQUIRED"));
    }

    @Test
    void POST_verifications_should_return_403_ACCOUNT_IN_FREEZE_PERIOD_with_freezeUntil() throws Exception {
        Instant freezeUntil = Instant.parse("2026-06-01T00:00:00Z");
        when(initiateUseCase.execute(any())).thenThrow(new AccountInFreezePeriodException(freezeUntil));

        mockMvc.perform(post("/api/v1/realname/verifications")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_IN_FREEZE_PERIOD"))
                .andExpect(jsonPath("$.freezeUntil").value("2026-06-01T00:00:00Z"));
    }

    @Test
    void POST_verifications_should_return_409_ALREADY_VERIFIED() throws Exception {
        when(initiateUseCase.execute(any())).thenThrow(new AlreadyVerifiedException());

        mockMvc.perform(post("/api/v1/realname/verifications")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REALNAME_ALREADY_VERIFIED"));
    }

    @Test
    void POST_verifications_should_return_409_ID_CARD_OCCUPIED() throws Exception {
        when(initiateUseCase.execute(any())).thenThrow(new IdCardOccupiedException());

        mockMvc.perform(post("/api/v1/realname/verifications")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REALNAME_ID_CARD_OCCUPIED"));
    }

    @Test
    void POST_verifications_should_return_429_with_RetryAfter_header() throws Exception {
        when(initiateUseCase.execute(any()))
                .thenThrow(new RateLimitedException("realname:account:7001", Duration.ofSeconds(60)));

        mockMvc.perform(post("/api/v1/realname/verifications")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void POST_verifications_should_return_502_PROVIDER_ERROR() throws Exception {
        when(initiateUseCase.execute(any())).thenThrow(new ProviderErrorException("aliyun biz err"));

        mockMvc.perform(post("/api/v1/realname/verifications")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("REALNAME_PROVIDER_ERROR"));
    }

    @Test
    void POST_verifications_should_return_503_PROVIDER_TIMEOUT() throws Exception {
        when(initiateUseCase.execute(any()))
                .thenThrow(new ProviderTimeoutException(new RuntimeException("aliyun slow")));

        mockMvc.perform(post("/api/v1/realname/verifications")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REALNAME_PROVIDER_TIMEOUT"));
    }

    @Test
    void POST_verifications_should_return_400_when_realName_blank_per_Jakarta_Validation() throws Exception {
        mockMvc.perform(post("/api/v1/realname/verifications")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"\",\"idCardNo\":\"11010119900101004X\",\"agreementVersion\":\"v1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GET_verifications_byBizId_should_return_404_PROFILE_NOT_FOUND_when_unknown() throws Exception {
        when(confirmUseCase.execute(any())).thenThrow(new RealnameProfileNotFoundException());

        mockMvc.perform(get("/api/v1/realname/verifications/biz-unknown").requestAttr("mbw.accountId", ACCOUNT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REALNAME_PROFILE_NOT_FOUND"));
    }

    @Test
    void GET_verifications_byBizId_should_return_403_ACCESS_DENIED_when_caller_not_owner() throws Exception {
        when(confirmUseCase.execute(any())).thenThrow(new RealnameProfileAccessDeniedException());

        mockMvc.perform(get("/api/v1/realname/verifications/biz-other").requestAttr("mbw.accountId", ACCOUNT_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REALNAME_ACCESS_DENIED"));
    }

    @Test
    void POST_verifications_should_return_200_when_provider_returns_VERIFIED_via_FailedReason_null_pattern()
            throws Exception {
        // Sanity check: the controller does not leak FailedReason on initiate happy path.
        when(initiateUseCase.execute(any())).thenReturn(new InitiateRealnameResult("biz-x", "https://l/x"));

        mockMvc.perform(post("/api/v1/realname/verifications")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedReason").doesNotExist());
    }

    @Test
    void GET_verifications_byBizId_should_carry_failedReason_when_FAILED() throws Exception {
        when(confirmUseCase.execute(any()))
                .thenReturn(new ConfirmRealnameResult(
                        "biz-uuid", RealnameStatus.FAILED, FailedReason.NAME_ID_MISMATCH, null));

        mockMvc.perform(get("/api/v1/realname/verifications/biz-uuid").requestAttr("mbw.accountId", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failedReason").value("NAME_ID_MISMATCH"));
    }
}
