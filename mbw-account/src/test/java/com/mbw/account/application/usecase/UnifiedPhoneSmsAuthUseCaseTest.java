package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.application.command.PhoneSmsAuthCommand;
import com.mbw.account.application.config.AuthRateLimitProperties;
import com.mbw.account.application.result.PhoneSmsAuthResult;
import com.mbw.account.domain.exception.AccountInFreezePeriodException;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.domain.exception.InvalidPhoneFormatException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.account.domain.service.TokenIssuer;
import com.mbw.shared.api.sms.AttemptOutcome;
import com.mbw.shared.api.sms.SmsCodeService;
import com.mbw.shared.web.RateLimitService;
import com.mbw.shared.web.RateLimitedException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unit tests for {@link UnifiedPhoneSmsAuthUseCase} (per ADR-0016 +
 * spec {@code phone-sms-auth/spec.md} FR-001 / FR-005 / FR-006 / FR-008).
 *
 * <p>11 scenarios cover the full state machine matrix:
 *
 * <ol>
 *   <li>Happy: 已注册 ACTIVE → updateLastLoginAt + tokens
 *   <li>Happy: 未注册 → 自动创建 + tokens
 *   <li>FROZEN account → AccountInFreezePeriodException disclosure (per
 *       spec D expose-frozen-account-status FR-002 + CL-006; supersedes
 *       prior anti-enumeration collapse)
 *   <li>ANONYMIZED account → 反枚举 InvalidCredentialsException
 *   <li>SMS code 错 → InvalidCredentialsException
 *   <li>限流 → RateLimitedException
 *   <li>Phone 格式错 → InvalidPhoneFormatException
 *   <li>并发同号 → DataIntegrityViolationException catch + fallthrough login
 *   <li>Wall-clock: FROZEN bypasses timing pad (per spec D FR-004)
 *   <li>Wall-clock: ANONYMIZED still pads to TIMING_TARGET
 *   <li>Wall-clock: ACTIVE login still pads to TIMING_TARGET
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UnifiedPhoneSmsAuthUseCaseTest {

    private static final String PHONE = "+8613800138000";
    private static final String CODE = "123456";
    private static final String CLIENT_IP = "203.0.113.7";

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private SmsCodeService smsCodeService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CredentialRepository credentialRepository;

    @Mock
    private TokenIssuer tokenIssuer;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private UnifiedPhoneSmsAuthUseCase useCase() {
        // Make TransactionTemplate.execute(callback) just call the callback synchronously
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        return new UnifiedPhoneSmsAuthUseCase(
                rateLimitService,
                smsCodeService,
                accountRepository,
                credentialRepository,
                tokenIssuer,
                refreshTokenRepository,
                transactionTemplate,
                new AuthRateLimitProperties(5, Duration.ofHours(24)));
    }

    private PhoneSmsAuthCommand cmd() {
        return new PhoneSmsAuthCommand(PHONE, CODE, CLIENT_IP);
    }

    private static Account account(long id, AccountStatus status) {
        Instant now = Instant.now();
        return Account.reconstitute(new AccountId(id), new PhoneNumber(PHONE), status, now, now, null);
    }

    private static Account frozenAccount(long id, Instant freezeUntil) {
        Instant now = Instant.now();
        return Account.reconstitute(
                new AccountId(id),
                new PhoneNumber(PHONE),
                AccountStatus.FROZEN,
                now,
                now,
                /* lastLoginAt= */ null,
                /* displayName= */ null,
                freezeUntil);
    }

    @Test
    void happy_already_registered_active_should_login_and_persist() {
        Account active = account(42L, AccountStatus.ACTIVE);
        when(smsCodeService.verify(PHONE, CODE)).thenReturn(new AttemptOutcome(true, 1, false));
        when(accountRepository.findByPhone(any(PhoneNumber.class))).thenReturn(Optional.of(active));
        when(tokenIssuer.signAccess(any(AccountId.class), any())).thenReturn("access-jwt");
        when(tokenIssuer.signRefresh()).thenReturn("refresh-token-256");

        PhoneSmsAuthResult result = useCase().execute(cmd());

        Assertions.assertThat(result.accountId()).isEqualTo(42L);
        Assertions.assertThat(result.accessToken()).isEqualTo("access-jwt");
        Assertions.assertThat(result.refreshToken()).isEqualTo("refresh-token-256");
        verify(accountRepository).updateLastLoginAt(eq(new AccountId(42L)), any(Instant.class));
        verify(refreshTokenRepository).save(any(RefreshTokenRecord.class));
        verify(accountRepository, never()).save(any(Account.class));
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void happy_unregistered_phone_should_auto_create_account_and_persist_credential() {
        when(smsCodeService.verify(PHONE, CODE)).thenReturn(new AttemptOutcome(true, 1, false));
        when(accountRepository.findByPhone(any(PhoneNumber.class))).thenReturn(Optional.empty());
        // Stub save: assign id 99 to the account passed in
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.assignId(new AccountId(99L));
            return a;
        });
        when(tokenIssuer.signAccess(any(AccountId.class), any())).thenReturn("access-jwt");
        when(tokenIssuer.signRefresh()).thenReturn("refresh-token-256");

        PhoneSmsAuthResult result = useCase().execute(cmd());

        Assertions.assertThat(result.accountId()).isEqualTo(99L);
        verify(accountRepository).save(any(Account.class));
        verify(credentialRepository).save(any(com.mbw.account.domain.model.PhoneCredential.class));
        verify(refreshTokenRepository).save(any(RefreshTokenRecord.class));
        // unified auth: register + immediate login → lastLoginAt set on register path too
        verify(accountRepository).updateLastLoginAt(eq(new AccountId(99L)), any(Instant.class));
    }

    @Test
    void frozen_account_should_disclose_account_in_freeze_period_with_freeze_until() {
        Instant freezeUntil = Instant.parse("2026-05-21T03:00:00Z");
        Account frozen = frozenAccount(33L, freezeUntil);
        when(smsCodeService.verify(PHONE, CODE)).thenReturn(new AttemptOutcome(true, 1, false));
        when(accountRepository.findByPhone(any(PhoneNumber.class))).thenReturn(Optional.of(frozen));

        assertThatThrownBy(() -> useCase().execute(cmd()))
                .isInstanceOf(AccountInFreezePeriodException.class)
                .satisfies(ex -> assertThat(((AccountInFreezePeriodException) ex).getFreezeUntil())
                        .isEqualTo(freezeUntil));

        verify(tokenIssuer, never()).signAccess(any());
        verify(accountRepository, never()).updateLastLoginAt(any(), any());
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void anonymized_account_should_throw_invalid_credentials_anti_enumeration() {
        Account anonymized = account(34L, AccountStatus.ANONYMIZED);
        when(smsCodeService.verify(PHONE, CODE)).thenReturn(new AttemptOutcome(true, 1, false));
        when(accountRepository.findByPhone(any(PhoneNumber.class))).thenReturn(Optional.of(anonymized));

        assertThatThrownBy(() -> useCase().execute(cmd())).isInstanceOf(InvalidCredentialsException.class);

        verify(tokenIssuer, never()).signAccess(any());
    }

    @Test
    void sms_code_invalid_should_throw_invalid_credentials() {
        when(smsCodeService.verify(PHONE, CODE)).thenReturn(new AttemptOutcome(false, 1, false));

        assertThatThrownBy(() -> useCase().execute(cmd())).isInstanceOf(InvalidCredentialsException.class);

        verify(accountRepository, never()).findByPhone(any());
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void rate_limit_should_block_before_sms_verify() {
        doThrow(new RateLimitedException("auth:" + PHONE, Duration.ofMinutes(30)))
                .when(rateLimitService)
                .consumeOrThrow(eq("auth:" + PHONE), any());

        assertThatThrownBy(() -> useCase().execute(cmd())).isInstanceOf(RateLimitedException.class);

        verify(smsCodeService, never()).verify(any(), any());
        verify(accountRepository, never()).findByPhone(any());
    }

    @Test
    void invalid_phone_format_should_throw_before_rate_limit() {
        PhoneSmsAuthCommand badPhone = new PhoneSmsAuthCommand("not-a-phone", CODE, CLIENT_IP);

        assertThatThrownBy(() -> useCase().execute(badPhone)).isInstanceOf(InvalidPhoneFormatException.class);

        verify(rateLimitService, never()).consumeOrThrow(any(), any());
        verify(smsCodeService, never()).verify(any(), any());
    }

    @Test
    void concurrent_same_phone_register_race_should_fallthrough_to_login() {
        Account active = account(50L, AccountStatus.ACTIVE);
        when(smsCodeService.verify(PHONE, CODE)).thenReturn(new AttemptOutcome(true, 1, false));
        // First findByPhone → empty (try register); second findByPhone (after race) → present
        when(accountRepository.findByPhone(any(PhoneNumber.class)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(active));
        // First save raises constraint violation (concurrent insert won the race)
        doAnswer(inv -> {
                    throw new DataIntegrityViolationException("uk_account_phone");
                })
                .when(accountRepository)
                .save(any(Account.class));
        when(tokenIssuer.signAccess(any(AccountId.class), any())).thenReturn("access-jwt");
        when(tokenIssuer.signRefresh()).thenReturn("refresh-token-256");

        PhoneSmsAuthResult result = useCase().execute(cmd());

        Assertions.assertThat(result.accountId()).isEqualTo(50L);
        // Login branch ran via the second findByPhone
        verify(accountRepository, times(2)).findByPhone(any(PhoneNumber.class));
        verify(accountRepository).updateLastLoginAt(eq(new AccountId(50L)), any(Instant.class));
    }

    @Test
    void frozen_path_should_bypass_timing_pad() {
        Instant freezeUntil = Instant.parse("2026-05-21T03:00:00Z");
        Account frozen = frozenAccount(33L, freezeUntil);
        when(smsCodeService.verify(PHONE, CODE)).thenReturn(new AttemptOutcome(true, 1, false));
        when(accountRepository.findByPhone(any(PhoneNumber.class))).thenReturn(Optional.of(frozen));

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> useCase().execute(cmd())).isInstanceOf(AccountInFreezePeriodException.class);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(elapsedMs)
                .as("FROZEN disclosure path bypasses TIMING_TARGET=400ms pad (per spec D FR-004)")
                .isLessThan(200L);
    }

    @Test
    void anonymized_path_should_still_pad_to_timing_target() {
        Account anonymized = account(34L, AccountStatus.ANONYMIZED);
        when(smsCodeService.verify(PHONE, CODE)).thenReturn(new AttemptOutcome(true, 1, false));
        when(accountRepository.findByPhone(any(PhoneNumber.class))).thenReturn(Optional.of(anonymized));

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> useCase().execute(cmd())).isInstanceOf(InvalidCredentialsException.class);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(elapsedMs)
                .as("ANONYMIZED still pads to TIMING_TARGET=400ms (anti-enumeration preserved)")
                .isGreaterThanOrEqualTo(380L);
    }

    @Test
    void active_login_should_still_pad_to_timing_target() {
        Account active = account(42L, AccountStatus.ACTIVE);
        when(smsCodeService.verify(PHONE, CODE)).thenReturn(new AttemptOutcome(true, 1, false));
        when(accountRepository.findByPhone(any(PhoneNumber.class))).thenReturn(Optional.of(active));
        when(tokenIssuer.signAccess(any(AccountId.class), any())).thenReturn("access-jwt");
        when(tokenIssuer.signRefresh()).thenReturn("refresh-token-256");

        long startNanos = System.nanoTime();
        PhoneSmsAuthResult result = useCase().execute(cmd());
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(result.accountId()).isEqualTo(42L);
        assertThat(elapsedMs)
                .as("ACTIVE happy path still pads to TIMING_TARGET=400ms (timing defense)")
                .isGreaterThanOrEqualTo(380L);
    }
}
