package com.mbw.account.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mbw.account.application.command.RegisterByPhoneCommand;
import com.mbw.account.application.result.RegisterByPhoneResult;
import com.mbw.account.application.usecase.RegisterByPhoneUseCase;
import com.mbw.account.application.usecase.RequestSmsCodeUseCase;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.web.exception.AccountWebExceptionAdvice;
import com.mbw.shared.web.RateLimitedException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * MockMvc unit-test for {@link AccountRegisterController} +
 * {@link AccountWebExceptionAdvice} (T15 + T16).
 *
 * <p>Uses {@code standaloneSetup} rather than {@code @WebMvcTest} so
 * we don't depend on a {@code @SpringBootApplication} (mbw-account
 * doesn't ship one — mbw-app is the only deployment unit). The test
 * focuses on HTTP-layer concerns (routing, validation, exception
 * mapping); business logic is exercised by the use case tests.
 */
@ExtendWith(MockitoExtension.class)
class AccountRegisterControllerTest {

    private static final String PHONE = "+8613800138000";
    private static final String CODE = "123456";

    @Mock
    private RequestSmsCodeUseCase requestSmsCodeUseCase;

    @Mock
    private RegisterByPhoneUseCase registerByPhoneUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AccountRegisterController(requestSmsCodeUseCase, registerByPhoneUseCase))
                .setControllerAdvice(new AccountWebExceptionAdvice())
                .build();
    }

    @Test
    void POST_sms_codes_should_return_200_on_success() throws Exception {
        mockMvc.perform(post("/api/v1/sms-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void POST_sms_codes_should_return_400_on_blank_phone() throws Exception {
        mockMvc.perform(post("/api/v1/sms-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_register_should_return_200_with_tokens() throws Exception {
        when(registerByPhoneUseCase.execute(any(RegisterByPhoneCommand.class)))
                .thenReturn(new RegisterByPhoneResult(42L, "access-jwt", "refresh-token"));

        mockMvc.perform(post("/api/v1/accounts/register-by-phone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(42))
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"));
    }

    @Test
    void POST_register_should_return_401_INVALID_CREDENTIALS_on_bad_code() throws Exception {
        doThrow(new InvalidCredentialsException()).when(registerByPhoneUseCase).execute(any());

        mockMvc.perform(post("/api/v1/accounts/register-by-phone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(InvalidCredentialsException.CODE));
    }

    @Test
    void POST_register_should_return_429_with_Retry_After_on_rate_limit() throws Exception {
        doThrow(new RateLimitedException("register:" + PHONE, Duration.ofMinutes(30)))
                .when(registerByPhoneUseCase)
                .execute(any());

        mockMvc.perform(post("/api/v1/accounts/register-by-phone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1800"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
