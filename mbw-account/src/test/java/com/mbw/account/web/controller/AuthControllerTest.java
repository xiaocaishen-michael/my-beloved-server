package com.mbw.account.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mbw.account.application.command.LoginByPasswordCommand;
import com.mbw.account.application.command.LoginByPhoneSmsCommand;
import com.mbw.account.application.command.RefreshTokenCommand;
import com.mbw.account.application.result.LoginByPasswordResult;
import com.mbw.account.application.result.LoginByPhoneSmsResult;
import com.mbw.account.application.result.RefreshTokenResult;
import com.mbw.account.application.usecase.LoginByPasswordUseCase;
import com.mbw.account.application.usecase.LoginByPhoneSmsUseCase;
import com.mbw.account.application.usecase.RefreshTokenUseCase;
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
    private static final String PASSWORD = "MyStrongP4ss";

    @Mock
    private LoginByPhoneSmsUseCase loginByPhoneSmsUseCase;

    @Mock
    private LoginByPasswordUseCase loginByPasswordUseCase;

    @Mock
    private RefreshTokenUseCase refreshTokenUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(loginByPhoneSmsUseCase, loginByPasswordUseCase, refreshTokenUseCase))
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

    @Test
    void POST_login_by_password_should_return_200_with_tokens() throws Exception {
        when(loginByPasswordUseCase.execute(any(LoginByPasswordCommand.class)))
                .thenReturn(new LoginByPasswordResult(42L, "access-jwt", "refresh-token"));

        mockMvc.perform(post("/api/v1/auth/login-by-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(42))
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"));
    }

    @Test
    void POST_login_by_password_should_return_400_on_blank_phone() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login-by-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_login_by_password_should_return_400_on_blank_password() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login-by-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_login_by_password_should_return_401_INVALID_CREDENTIALS_on_bad_password() throws Exception {
        doThrow(new InvalidCredentialsException()).when(loginByPasswordUseCase).execute(any());

        mockMvc.perform(post("/api/v1/auth/login-by-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(InvalidCredentialsException.CODE));
    }

    @Test
    void POST_login_by_password_should_return_429_with_Retry_After_on_rate_limit() throws Exception {
        doThrow(new RateLimitedException("auth:1.2.3.4", Duration.ofHours(2)))
                .when(loginByPasswordUseCase)
                .execute(any());

        mockMvc.perform(post("/api/v1/auth/login-by-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "7200"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void POST_refresh_token_should_return_200_with_tokens() throws Exception {
        when(refreshTokenUseCase.execute(any(RefreshTokenCommand.class)))
                .thenReturn(new RefreshTokenResult(42L, "new-access-jwt", "new-refresh-token"));

        mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old-refresh-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(42))
                .andExpect(jsonPath("$.accessToken").value("new-access-jwt"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh-token"));
    }

    @Test
    void POST_refresh_token_should_return_400_on_blank_token() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_refresh_token_should_return_401_INVALID_CREDENTIALS_on_invalid_token() throws Exception {
        doThrow(new InvalidCredentialsException()).when(refreshTokenUseCase).execute(any());

        mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"any-old-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(InvalidCredentialsException.CODE));
    }

    @Test
    void POST_refresh_token_should_return_429_with_Retry_After_on_rate_limit() throws Exception {
        doThrow(new RateLimitedException("refresh:1.2.3.4", Duration.ofSeconds(60)))
                .when(refreshTokenUseCase)
                .execute(any());

        mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"any-token\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
