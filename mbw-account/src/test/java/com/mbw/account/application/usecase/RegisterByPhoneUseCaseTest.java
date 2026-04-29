package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.application.command.RegisterByPhoneCommand;
import com.mbw.account.application.result.RegisterByPhoneResult;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.domain.exception.InvalidPhoneFormatException;
import com.mbw.account.domain.exception.WeakPasswordException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.PasswordCredential;
import com.mbw.account.domain.model.PasswordHash;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.account.domain.service.PasswordHasher;
import com.mbw.account.domain.service.TokenIssuer;
import com.mbw.shared.api.sms.AttemptOutcome;
import com.mbw.shared.api.sms.SmsCodeService;
import com.mbw.shared.web.RateLimitService;
import com.mbw.shared.web.RateLimitedException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RegisterByPhoneUseCaseTest {

    private static final String PHONE = "+8613800138000";
    private static final String CODE = "123456";
    private static final String VALID_PASSWORD = "MyStrongP4ss";
    private static final String VALID_BCRYPT = "$2a$08$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final long ASSIGNED_ID = 42L;

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private SmsCodeService smsCodeService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CredentialRepository credentialRepository;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private TokenIssuer tokenIssuer;

    @Mock
    private TransactionTemplate transactionTemplate;

    private RegisterByPhoneUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RegisterByPhoneUseCase(
                rateLimitService,
                smsCodeService,
                accountRepository,
                credentialRepository,
                passwordHasher,
                tokenIssuer,
                transactionTemplate);

        // Pass-through transaction template: invoke the callback directly
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });

        // Default success path: code matches, account.save assigns id, tokens
        when(smsCodeService.verify(eq(PHONE), eq(CODE))).thenReturn(new AttemptOutcome(true, 0, false));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.assignId(new AccountId(ASSIGNED_ID));
            return a;
        });
        when(tokenIssuer.signAccess(any(AccountId.class))).thenReturn("access-jwt");
        when(tokenIssuer.signRefresh()).thenReturn("refresh-token");
        when(passwordHasher.hash(any())).thenReturn(new PasswordHash(VALID_BCRYPT));
    }

    @Test
    void should_return_tokens_when_unregistered_no_password() {
        RegisterByPhoneResult result = useCase.execute(new RegisterByPhoneCommand(PHONE, CODE, Optional.empty()));

        assertThat(result.accountId()).isEqualTo(ASSIGNED_ID);
        assertThat(result.accessToken()).isEqualTo("access-jwt");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");

        verify(rateLimitService).consumeOrThrow(eq("register:" + PHONE), any());
        verify(smsCodeService).verify(PHONE, CODE);
        verify(accountRepository).save(any(Account.class));
        verify(credentialRepository, times(1)).save(any(PhoneCredential.class));
        verify(credentialRepository, never()).save(any(PasswordCredential.class));
        verify(tokenIssuer).signAccess(any(AccountId.class));
        verify(tokenIssuer).signRefresh();
    }

    @Test
    void should_persist_password_credential_when_password_provided() {
        useCase.execute(new RegisterByPhoneCommand(PHONE, CODE, Optional.of(VALID_PASSWORD)));

        verify(passwordHasher).hash(VALID_PASSWORD);
        verify(credentialRepository).save(any(PhoneCredential.class));
        verify(credentialRepository).save(any(PasswordCredential.class));
    }

    @Test
    void should_propagate_InvalidPhoneFormat_for_bad_phone() {
        assertThatThrownBy(() -> useCase.execute(new RegisterByPhoneCommand("+12025550100", CODE, Optional.empty())))
                .isInstanceOf(InvalidPhoneFormatException.class);

        verify(rateLimitService, never()).consumeOrThrow(any(), any());
        verify(smsCodeService, never()).verify(any(), any());
    }

    @Test
    void should_propagate_WeakPasswordException_for_bad_password() {
        assertThatThrownBy(() -> useCase.execute(new RegisterByPhoneCommand(PHONE, CODE, Optional.of("weak"))))
                .isInstanceOf(WeakPasswordException.class);

        verify(rateLimitService, never()).consumeOrThrow(any(), any());
        verify(smsCodeService, never()).verify(any(), any());
    }

    @Test
    void should_propagate_RateLimitedException_for_register_24h_lock() {
        doThrow(new RateLimitedException("register:" + PHONE, Duration.ofMinutes(30)))
                .when(rateLimitService)
                .consumeOrThrow(eq("register:" + PHONE), any());

        assertThatThrownBy(() -> useCase.execute(new RegisterByPhoneCommand(PHONE, CODE, Optional.empty())))
                .isInstanceOf(RateLimitedException.class);

        verify(smsCodeService, never()).verify(any(), any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void should_throw_InvalidCredentials_when_code_verification_fails() {
        when(smsCodeService.verify(eq(PHONE), eq(CODE))).thenReturn(new AttemptOutcome(false, 1, false));

        assertThatThrownBy(() -> useCase.execute(new RegisterByPhoneCommand(PHONE, CODE, Optional.empty())))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(transactionTemplate, never()).execute(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void should_throw_InvalidCredentials_when_code_already_invalidated() {
        when(smsCodeService.verify(eq(PHONE), eq(CODE))).thenReturn(new AttemptOutcome(false, 3, true));

        assertThatThrownBy(() -> useCase.execute(new RegisterByPhoneCommand(PHONE, CODE, Optional.empty())))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void should_map_DataIntegrityViolation_to_InvalidCredentials() {
        // doThrow().when() avoids re-invoking the prior thenAnswer stub during the
        // stubbing call (which would NPE on the null callback)
        doThrow(new DataIntegrityViolationException("uk_account_phone"))
                .when(transactionTemplate)
                .execute(any());

        assertThatThrownBy(() -> useCase.execute(new RegisterByPhoneCommand(PHONE, CODE, Optional.empty())))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void execute_should_meet_timing_defense_target() {
        long startNanos = System.nanoTime();

        useCase.execute(new RegisterByPhoneCommand(PHONE, CODE, Optional.empty()));

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        // FR-013 wraps the entire flow in TimingDefenseExecutor with 400ms target;
        // even fully-mocked deps (microsecond execution) should be padded to ~400ms
        assertThat(elapsedMs).isGreaterThanOrEqualTo(395);
    }
}
