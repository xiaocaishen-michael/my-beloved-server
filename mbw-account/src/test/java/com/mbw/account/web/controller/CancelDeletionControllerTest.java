package com.mbw.account.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mbw.account.application.command.CancelDeletionCommand;
import com.mbw.account.application.command.SendCancelDeletionCodeCommand;
import com.mbw.account.application.result.CancelDeletionResult;
import com.mbw.account.application.usecase.CancelDeletionUseCase;
import com.mbw.account.application.usecase.SendCancelDeletionCodeUseCase;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.web.exception.AccountWebExceptionAdvice;
import com.mbw.shared.api.sms.SmsSendException;
import com.mbw.shared.web.RateLimitedException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * MockMvc unit-test for {@link CancelDeletionController} +
 * {@link AccountWebExceptionAdvice} (cancel-deletion spec T5).
 *
 * <p>Uses {@code standaloneSetup} for the same reason as
 * {@link AccountDeletionControllerTest}: {@code mbw-account} is a
 * library module without a {@code @SpringBootApplication}, so
 * {@code @WebMvcTest} cannot bootstrap. The
 * {@code AccountWebExceptionAdvice} is wired via
 * {@code setControllerAdvice} to verify the full exception → HTTP
 * mapping (401 INVALID_CREDENTIALS / 429 RATE_LIMITED / 503 SMS_SEND_FAILED).
 */
@ExtendWith(MockitoExtension.class)
class CancelDeletionControllerTest {

    private static final String PHONE = "+8613800138000";

    @Mock
    private SendCancelDeletionCodeUseCase sendCancelDeletionCodeUseCase;

    @Mock
    private CancelDeletionUseCase cancelDeletionUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new CancelDeletionController(sendCancelDeletionCodeUseCase, cancelDeletionUseCase))
                .setControllerAdvice(new AccountWebExceptionAdvice())
                .build();
    }

    // ── POST /api/v1/auth/cancel-deletion/sms-codes ─────────────────────────

    @Test
    void POST_sendCode_should_return_200_when_phone_valid() throws Exception {
        mockMvc.perform(post("/api/v1/auth/cancel-deletion/sms-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\"}"))
                .andExpect(status().isOk());

        verify(sendCancelDeletionCodeUseCase).execute(any(SendCancelDeletionCodeCommand.class));
    }

    @Test
    void POST_sendCode_should_return_400_when_phone_missing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/cancel-deletion/sms-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_sendCode_should_return_400_when_phone_format_invalid_at_web_layer() throws Exception {
        // Web-layer @Pattern requires E.164 generic shape; "abc" fails before use case sees it.
        mockMvc.perform(post("/api/v1/auth/cancel-deletion/sms-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"abc\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_sendCode_should_return_429_with_Retry_After_when_rate_limited() throws Exception {
        doThrow(new RateLimitedException("cancel-code:phone:abc", Duration.ofSeconds(55)))
                .when(sendCancelDeletionCodeUseCase)
                .execute(any());

        mockMvc.perform(post("/api/v1/auth/cancel-deletion/sms-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "55"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void POST_sendCode_should_return_503_SMS_SEND_FAILED_when_sms_gateway_fails() throws Exception {
        doThrow(new SmsSendException("gateway error"))
                .when(sendCancelDeletionCodeUseCase)
                .execute(any());

        mockMvc.perform(post("/api/v1/auth/cancel-deletion/sms-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SMS_SEND_FAILED"));
    }

    @Test
    void POST_sendCode_should_extract_clientIp_from_first_XFF_entry() throws Exception {
        ArgumentCaptor<SendCancelDeletionCodeCommand> captor =
                ArgumentCaptor.forClass(SendCancelDeletionCodeCommand.class);

        mockMvc.perform(post("/api/v1/auth/cancel-deletion/sms-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\"}")
                        .header("X-Forwarded-For", "1.2.3.4, 10.0.0.1"))
                .andExpect(status().isOk());

        verify(sendCancelDeletionCodeUseCase).execute(captor.capture());
        assertThat(captor.getValue().clientIp()).isEqualTo("1.2.3.4");
    }

    // ── POST /api/v1/auth/cancel-deletion ───────────────────────────────────

    @Test
    void POST_cancel_should_return_200_with_LoginResponse_when_phone_and_code_valid() throws Exception {
        when(cancelDeletionUseCase.execute(any()))
                .thenReturn(new CancelDeletionResult(42L, "access.jwt", "refresh-raw"));

        mockMvc.perform(post("/api/v1/auth/cancel-deletion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(42))
                .andExpect(jsonPath("$.accessToken").value("access.jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-raw"));

        verify(cancelDeletionUseCase).execute(any(CancelDeletionCommand.class));
    }

    @Test
    void POST_cancel_should_return_400_when_phone_missing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/cancel-deletion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_cancel_should_return_400_when_code_missing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/cancel-deletion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_cancel_should_return_400_when_code_not_6_digits() throws Exception {
        mockMvc.perform(post("/api/v1/auth/cancel-deletion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"code\":\"abc123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_cancel_should_return_400_when_phone_format_invalid_at_web_layer() throws Exception {
        mockMvc.perform(post("/api/v1/auth/cancel-deletion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"not-a-phone\",\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_cancel_should_return_401_INVALID_CREDENTIALS_when_use_case_rejects() throws Exception {
        doThrow(new InvalidCredentialsException()).when(cancelDeletionUseCase).execute(any());

        mockMvc.perform(post("/api/v1/auth/cancel-deletion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(InvalidCredentialsException.CODE));
    }

    @Test
    void POST_cancel_should_return_429_with_Retry_After_when_rate_limited() throws Exception {
        doThrow(new RateLimitedException("cancel-submit:phone:abc", Duration.ofSeconds(30)))
                .when(cancelDeletionUseCase)
                .execute(any());

        mockMvc.perform(post("/api/v1/auth/cancel-deletion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"code\":\"123456\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
