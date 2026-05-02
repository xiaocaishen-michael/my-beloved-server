package com.mbw.account.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mbw.account.application.command.LoginByPhoneSmsCommand;
import com.mbw.account.application.result.LoginByPhoneSmsResult;
import com.mbw.account.application.usecase.LoginByPhoneSmsUseCase;
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
 * MockMvc unit-test for {@link AuthController} +
 * {@link AccountWebExceptionAdvice} (T9).
 *
 * <p>Same {@code standaloneSetup} pattern as
 * {@code AccountRegisterControllerTest} — mbw-account is a library
 * module without its own {@code @SpringBootApplication}.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private static final String PHONE = "+8613800138000";
    private static final String CODE = "123456";

    @Mock
    private LoginByPhoneSmsUseCase loginByPhoneSmsUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(loginByPhoneSmsUseCase))
                .setControllerAdvice(new AccountWebExceptionAdvice())
                .build();
    }

    @Test
    void POST_login_by_phone_sms_should_return_200_with_tokens() throws Exception {
        when(loginByPhoneSmsUseCase.execute(any(LoginByPhoneSmsCommand.class)))
                .thenReturn(new LoginByPhoneSmsResult(42L, "access-jwt", "refresh-token"));

        mockMvc.perform(post("/api/v1/auth/login-by-phone-sms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(42))
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"));
    }

    @Test
    void POST_login_should_return_400_on_blank_phone() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login-by-phone-sms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"\",\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_login_should_return_400_on_non_6_digit_code() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login-by-phone-sms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"code\":\"abc\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_login_should_return_401_INVALID_CREDENTIALS_on_bad_code() throws Exception {
        doThrow(new InvalidCredentialsException()).when(loginByPhoneSmsUseCase).execute(any());

        mockMvc.perform(post("/api/v1/auth/login-by-phone-sms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(InvalidCredentialsException.CODE));
    }

    @Test
    void POST_login_should_return_429_with_Retry_After_on_rate_limit() throws Exception {
        doThrow(new RateLimitedException("login:" + PHONE, Duration.ofHours(1)))
                .when(loginByPhoneSmsUseCase)
                .execute(any());

        mockMvc.perform(post("/api/v1/auth/login-by-phone-sms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
