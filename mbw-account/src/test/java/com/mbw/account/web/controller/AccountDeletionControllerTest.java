package com.mbw.account.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mbw.account.application.command.DeleteAccountCommand;
import com.mbw.account.application.command.SendDeletionCodeCommand;
import com.mbw.account.application.usecase.DeleteAccountUseCase;
import com.mbw.account.application.usecase.SendDeletionCodeUseCase;
import com.mbw.account.domain.exception.AccountInactiveException;
import com.mbw.account.domain.exception.AccountNotFoundException;
import com.mbw.account.domain.exception.InvalidDeletionCodeException;
import com.mbw.account.domain.model.AccountId;
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
 * MockMvc unit-test for {@link AccountDeletionController} +
 * {@link AccountWebExceptionAdvice} (delete-account spec T9).
 *
 * <p>Uses {@code standaloneSetup} — {@code mbw-account} is a library
 * module with no {@code @SpringBootApplication}, so {@code @WebMvcTest}
 * cannot find a configuration class to bootstrap. The
 * {@code AccountWebExceptionAdvice} is wired via
 * {@code setControllerAdvice} to verify the full exception → HTTP mapping.
 */
@ExtendWith(MockitoExtension.class)
class AccountDeletionControllerTest {

    private static final AccountId ACCOUNT_ID = new AccountId(42L);

    @Mock
    private SendDeletionCodeUseCase sendDeletionCodeUseCase;

    @Mock
    private DeleteAccountUseCase deleteAccountUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AccountDeletionController(sendDeletionCodeUseCase, deleteAccountUseCase))
                .setControllerAdvice(new AccountWebExceptionAdvice())
                .build();
    }

    // ── POST /api/v1/accounts/me/deletion-codes ──────────────────────────────

    @Test
    void POST_sendCode_should_return_204_when_authenticated_and_active() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/me/deletion-codes").requestAttr("mbw.accountId", ACCOUNT_ID))
                .andExpect(status().isNoContent());

        verify(sendDeletionCodeUseCase).execute(any(SendDeletionCodeCommand.class));
    }

    @Test
    void POST_sendCode_should_return_401_AUTH_FAILED_when_no_auth() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/me/deletion-codes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
    }

    @Test
    void POST_sendCode_should_return_401_AUTH_FAILED_when_attribute_has_wrong_type() throws Exception {
        // Defensive: filter sets a non-AccountId value (e.g. Long) → treated as missing auth.
        mockMvc.perform(post("/api/v1/accounts/me/deletion-codes").requestAttr("mbw.accountId", 42L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
    }

    @Test
    void POST_sendCode_should_return_401_AUTH_FAILED_when_account_inactive() throws Exception {
        doThrow(new AccountInactiveException()).when(sendDeletionCodeUseCase).execute(any());

        mockMvc.perform(post("/api/v1/accounts/me/deletion-codes").requestAttr("mbw.accountId", ACCOUNT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
    }

    @Test
    void POST_sendCode_should_return_401_AUTH_FAILED_when_account_not_found() throws Exception {
        doThrow(new AccountNotFoundException()).when(sendDeletionCodeUseCase).execute(any());

        mockMvc.perform(post("/api/v1/accounts/me/deletion-codes").requestAttr("mbw.accountId", ACCOUNT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
    }

    @Test
    void POST_sendCode_should_return_429_with_Retry_After_when_rate_limited() throws Exception {
        doThrow(new RateLimitedException("delete-code:account:42", Duration.ofSeconds(55)))
                .when(sendDeletionCodeUseCase)
                .execute(any());

        mockMvc.perform(post("/api/v1/accounts/me/deletion-codes").requestAttr("mbw.accountId", ACCOUNT_ID))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "55"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void POST_sendCode_should_return_503_SMS_SEND_FAILED_when_sms_send_fails() throws Exception {
        doThrow(new SmsSendException("gateway error"))
                .when(sendDeletionCodeUseCase)
                .execute(any());

        mockMvc.perform(post("/api/v1/accounts/me/deletion-codes").requestAttr("mbw.accountId", ACCOUNT_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SMS_SEND_FAILED"));
    }

    @Test
    void POST_sendCode_should_extract_clientIp_from_first_XFF_entry() throws Exception {
        ArgumentCaptor<SendDeletionCodeCommand> captor = ArgumentCaptor.forClass(SendDeletionCodeCommand.class);

        mockMvc.perform(post("/api/v1/accounts/me/deletion-codes")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .header("X-Forwarded-For", "1.2.3.4, 10.0.0.1"))
                .andExpect(status().isNoContent());

        verify(sendDeletionCodeUseCase).execute(captor.capture());
        assertThat(captor.getValue().clientIp()).isEqualTo("1.2.3.4");
    }

    // ── POST /api/v1/accounts/me/deletion ────────────────────────────────────

    @Test
    void POST_delete_should_return_204_when_authenticated_and_code_valid() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/me/deletion")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isNoContent());

        verify(deleteAccountUseCase).execute(any(DeleteAccountCommand.class));
    }

    @Test
    void POST_delete_should_return_400_when_code_missing() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/me/deletion")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_delete_should_return_400_when_code_not_6_digits() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/me/deletion")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"abc123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_delete_should_return_401_AUTH_FAILED_when_no_auth() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/me/deletion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
    }

    @Test
    void POST_delete_should_return_401_INVALID_DELETION_CODE_when_code_wrong_or_expired() throws Exception {
        doThrow(new InvalidDeletionCodeException()).when(deleteAccountUseCase).execute(any());

        mockMvc.perform(post("/api/v1/accounts/me/deletion")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(InvalidDeletionCodeException.CODE));
    }

    @Test
    void POST_delete_should_return_429_with_Retry_After_when_rate_limited() throws Exception {
        doThrow(new RateLimitedException("delete-submit:account:42", Duration.ofSeconds(30)))
                .when(deleteAccountUseCase)
                .execute(any());

        mockMvc.perform(post("/api/v1/accounts/me/deletion")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void POST_delete_should_return_401_AUTH_FAILED_when_account_not_found_concurrently() throws Exception {
        doThrow(new AccountNotFoundException()).when(deleteAccountUseCase).execute(any());

        mockMvc.perform(post("/api/v1/accounts/me/deletion")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
    }

    @Test
    void POST_delete_should_return_400_when_code_has_wrong_length() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/me/deletion")
                        .requestAttr("mbw.accountId", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"12345\"}"))
                .andExpect(status().isBadRequest());
    }
}
