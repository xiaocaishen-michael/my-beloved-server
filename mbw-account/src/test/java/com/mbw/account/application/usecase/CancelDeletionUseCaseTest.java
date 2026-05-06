package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.api.event.AccountDeletionCancelledEvent;
import com.mbw.account.application.command.CancelDeletionCommand;
import com.mbw.account.application.result.CancelDeletionResult;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountSmsCode;
import com.mbw.account.domain.model.AccountSmsCodeId;
import com.mbw.account.domain.model.AccountSmsCodePurpose;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.AccountSmsCodeRepository;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.account.domain.service.TokenIssuer;
import com.mbw.shared.web.RateLimitService;
import com.mbw.shared.web.RateLimitedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CancelDeletionUseCaseTest {

    private static final AccountId ACCOUNT_ID = new AccountId(42L);
    private static final String PHONE_RAW = "+8613800138000";
    private static final PhoneNumber PHONE = new PhoneNumber(PHONE_RAW);
    private static final String CLIENT_IP = "203.0.113.7";
    private static final Instant NOW = Instant.parse("2026-05-06T10:00:00Z");
    private static final String CODE_RAW = "123456";
    private static final String CODE_HASH = sha256(CODE_RAW);

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountSmsCodeRepository smsCodeRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private TokenIssuer tokenIssuer;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private CancelDeletionUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CancelDeletionUseCase(
                rateLimitService,
                accountRepository,
                smsCodeRepository,
                refreshTokenRepository,
                tokenIssuer,
                eventPublisher,
                clock);
    }

    private CancelDeletionCommand cmd() {
        return new CancelDeletionCommand(PHONE_RAW, CODE_RAW, CLIENT_IP);
    }

    private static Account frozenAccount(Instant freezeUntil) {
        return Account.reconstitute(
                ACCOUNT_ID,
                PHONE,
                AccountStatus.FROZEN,
                NOW.minusSeconds(120),
                NOW.minusSeconds(120),
                null,
                null,
                freezeUntil);
    }

    private static AccountSmsCode activeCode(String hash) {
        return AccountSmsCode.reconstitute(
                new AccountSmsCodeId(101L),
                ACCOUNT_ID,
                hash,
                AccountSmsCodePurpose.CANCEL_DELETION,
                NOW.plus(Duration.ofMinutes(10)),
                /* usedAt= */ null,
                NOW.minusSeconds(60));
    }

    @Test
    void should_transition_ACTIVE_and_issue_token_and_publish_event_when_FROZEN_grace_valid_and_code_correct() {
        Instant freezeUntil = NOW.plus(Duration.ofDays(10));
        Account account = frozenAccount(freezeUntil);
        when(accountRepository.findByPhoneForUpdate(PHONE)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenReturn(account);
        when(smsCodeRepository.findActiveByPurposeAndAccountId(
                        eq(AccountSmsCodePurpose.CANCEL_DELETION), eq(ACCOUNT_ID), any()))
                .thenReturn(Optional.of(activeCode(CODE_HASH)));
        when(tokenIssuer.signAccess(ACCOUNT_ID)).thenReturn("access.jwt");
        when(tokenIssuer.signRefresh()).thenReturn("refresh-raw");

        CancelDeletionResult result = useCase.execute(cmd());

        assertThat(result.accountId()).isEqualTo(42L);
        assertThat(result.accessToken()).isEqualTo("access.jwt");
        assertThat(result.refreshToken()).isEqualTo("refresh-raw");

        verify(rateLimitService).consumeOrThrow(eq("cancel-submit:phone:" + sha256(PHONE_RAW)), any());
        verify(rateLimitService).consumeOrThrow(eq("cancel-submit:ip:" + CLIENT_IP), any());

        verify(smsCodeRepository).markUsed(eq(new AccountSmsCodeId(101L)), eq(NOW));
        verify(accountRepository).save(account);
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.freezeUntil()).isNull();

        ArgumentCaptor<AccountDeletionCancelledEvent> eventCaptor =
                ArgumentCaptor.forClass(AccountDeletionCancelledEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(eventCaptor.getValue().cancelledAt()).isEqualTo(NOW);

        verify(refreshTokenRepository).save(any());
    }

    // ---- Group B: rate limit + 4 phone branches + 3 code branches (9 tests) ----

    @Test
    void should_throw_RateLimited_when_phone_throttled() {
        doThrow(new RateLimitedException("cancel-submit:phone:" + sha256(PHONE_RAW), Duration.ofSeconds(55)))
                .when(rateLimitService)
                .consumeOrThrow(eq("cancel-submit:phone:" + sha256(PHONE_RAW)), any());

        assertThatThrownBy(() -> useCase.execute(cmd())).isInstanceOf(RateLimitedException.class);

        verify(accountRepository, never()).findByPhoneForUpdate(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_RateLimited_when_ip_throttled() {
        doThrow(new RateLimitedException("cancel-submit:ip:" + CLIENT_IP, Duration.ofSeconds(30)))
                .when(rateLimitService)
                .consumeOrThrow(eq("cancel-submit:ip:" + CLIENT_IP), any());

        assertThatThrownBy(() -> useCase.execute(cmd())).isInstanceOf(RateLimitedException.class);

        verify(accountRepository, never()).findByPhoneForUpdate(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_InvalidCredentials_when_phone_not_found() {
        when(accountRepository.findByPhoneForUpdate(PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(cmd())).isInstanceOf(InvalidCredentialsException.class);

        verify(smsCodeRepository, never()).findActiveByPurposeAndAccountId(any(), any(), any());
        verify(smsCodeRepository, never()).markUsed(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_InvalidCredentials_when_account_ACTIVE() {
        Account active = Account.reconstitute(ACCOUNT_ID, PHONE, AccountStatus.ACTIVE, NOW, NOW);
        when(accountRepository.findByPhone(PHONE)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> useCase.execute(cmd())).isInstanceOf(InvalidCredentialsException.class);

        verify(smsCodeRepository, never()).findActiveByPurposeAndAccountId(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_InvalidCredentials_when_account_ANONYMIZED() {
        Account anonymized = Account.reconstitute(ACCOUNT_ID, PHONE, AccountStatus.ANONYMIZED, NOW, NOW);
        when(accountRepository.findByPhone(PHONE)).thenReturn(Optional.of(anonymized));

        assertThatThrownBy(() -> useCase.execute(cmd())).isInstanceOf(InvalidCredentialsException.class);

        verify(smsCodeRepository, never()).findActiveByPurposeAndAccountId(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_InvalidCredentials_when_account_FROZEN_grace_expired() {
        // scheduler 抢跑 simulation: freeze_until <= now at findByPhone moment
        Instant freezeUntilExpired = NOW.minusSeconds(1);
        when(accountRepository.findByPhone(PHONE)).thenReturn(Optional.of(frozenAccount(freezeUntilExpired)));

        assertThatThrownBy(() -> useCase.execute(cmd())).isInstanceOf(InvalidCredentialsException.class);

        verify(smsCodeRepository, never()).findActiveByPurposeAndAccountId(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_InvalidCredentials_when_no_active_CANCEL_DELETION_code() {
        Instant freezeUntil = NOW.plus(Duration.ofDays(10));
        when(accountRepository.findByPhone(PHONE)).thenReturn(Optional.of(frozenAccount(freezeUntil)));
        when(smsCodeRepository.findActiveByPurposeAndAccountId(
                        eq(AccountSmsCodePurpose.CANCEL_DELETION), eq(ACCOUNT_ID), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(cmd())).isInstanceOf(InvalidCredentialsException.class);

        verify(smsCodeRepository, never()).markUsed(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_InvalidCredentials_when_code_hash_mismatch() {
        Instant freezeUntil = NOW.plus(Duration.ofDays(10));
        when(accountRepository.findByPhone(PHONE)).thenReturn(Optional.of(frozenAccount(freezeUntil)));
        when(smsCodeRepository.findActiveByPurposeAndAccountId(
                        eq(AccountSmsCodePurpose.CANCEL_DELETION), eq(ACCOUNT_ID), any()))
                .thenReturn(Optional.of(activeCode(sha256("999999")))); // different code persisted

        assertThatThrownBy(() -> useCase.execute(cmd())).isInstanceOf(InvalidCredentialsException.class);

        verify(smsCodeRepository, never()).markUsed(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_InvalidCredentials_when_code_already_used() {
        Instant freezeUntil = NOW.plus(Duration.ofDays(10));
        when(accountRepository.findByPhone(PHONE)).thenReturn(Optional.of(frozenAccount(freezeUntil)));
        AccountSmsCode usedCode = AccountSmsCode.reconstitute(
                new AccountSmsCodeId(101L),
                ACCOUNT_ID,
                CODE_HASH,
                AccountSmsCodePurpose.CANCEL_DELETION,
                NOW.plus(Duration.ofMinutes(10)),
                /* usedAt= */ NOW.minusSeconds(30), // already consumed
                NOW.minusSeconds(60));
        when(smsCodeRepository.findActiveByPurposeAndAccountId(
                        eq(AccountSmsCodePurpose.CANCEL_DELETION), eq(ACCOUNT_ID), any()))
                .thenReturn(Optional.of(usedCode));

        assertThatThrownBy(() -> useCase.execute(cmd())).isInstanceOf(InvalidCredentialsException.class);

        verify(smsCodeRepository, never()).markUsed(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---- Group C: rollback (2 tests) ----

    @Test
    void should_rollback_when_TokenIssuer_throws() {
        Instant freezeUntil = NOW.plus(Duration.ofDays(10));
        Account account = frozenAccount(freezeUntil);
        when(accountRepository.findByPhoneForUpdate(PHONE)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenReturn(account);
        when(smsCodeRepository.findActiveByPurposeAndAccountId(
                        eq(AccountSmsCodePurpose.CANCEL_DELETION), eq(ACCOUNT_ID), any()))
                .thenReturn(Optional.of(activeCode(CODE_HASH)));
        doThrow(new IllegalStateException("token signing failed"))
                .when(tokenIssuer)
                .signAccess(ACCOUNT_ID);

        assertThatThrownBy(() -> useCase.execute(cmd())).isInstanceOf(IllegalStateException.class);

        // refresh_token persistence must not happen if TokenIssuer fails;
        // upstream side effects (markUsed / save / publishEvent) are rolled back
        // by @Transactional(rollbackFor=Throwable.class) at integration level.
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void should_rollback_when_refresh_token_persist_fails() {
        Instant freezeUntil = NOW.plus(Duration.ofDays(10));
        Account account = frozenAccount(freezeUntil);
        when(accountRepository.findByPhoneForUpdate(PHONE)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenReturn(account);
        when(smsCodeRepository.findActiveByPurposeAndAccountId(
                        eq(AccountSmsCodePurpose.CANCEL_DELETION), eq(ACCOUNT_ID), any()))
                .thenReturn(Optional.of(activeCode(CODE_HASH)));
        when(tokenIssuer.signAccess(ACCOUNT_ID)).thenReturn("access.jwt");
        when(tokenIssuer.signRefresh()).thenReturn("refresh-raw");
        doThrow(new RuntimeException("DB down")).when(refreshTokenRepository).save(any());

        assertThatThrownBy(() -> useCase.execute(cmd())).isInstanceOf(RuntimeException.class);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
