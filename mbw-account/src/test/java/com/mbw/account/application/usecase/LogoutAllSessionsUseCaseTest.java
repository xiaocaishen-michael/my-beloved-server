package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.application.command.LogoutAllSessionsCommand;
import com.mbw.account.application.result.LogoutAllSessionsResult;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.shared.web.RateLimitService;
import com.mbw.shared.web.RateLimitedException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LogoutAllSessionsUseCaseTest {

    private static final AccountId ACCOUNT_ID = new AccountId(42L);
    private static final String CLIENT_IP = "203.0.113.7";

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private LogoutAllSessionsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new LogoutAllSessionsUseCase(rateLimitService, refreshTokenRepository);
    }

    @Test
    void should_revoke_all_and_return_count_when_account_has_active_tokens() {
        when(refreshTokenRepository.revokeAllForAccount(eq(ACCOUNT_ID), any(Instant.class)))
                .thenReturn(3);

        LogoutAllSessionsResult result = useCase.execute(new LogoutAllSessionsCommand(ACCOUNT_ID, CLIENT_IP));

        assertThat(result.revokedCount()).isEqualTo(3);
        verify(rateLimitService).consumeOrThrow(eq("logout-all:" + ACCOUNT_ID.value()), any());
        verify(rateLimitService).consumeOrThrow(eq("logout-all:" + CLIENT_IP), any());
        verify(refreshTokenRepository).revokeAllForAccount(eq(ACCOUNT_ID), any(Instant.class));
    }

    @Test
    void should_succeed_with_zero_count_when_account_has_no_active_tokens() {
        when(refreshTokenRepository.revokeAllForAccount(eq(ACCOUNT_ID), any(Instant.class)))
                .thenReturn(0);

        LogoutAllSessionsResult result = useCase.execute(new LogoutAllSessionsCommand(ACCOUNT_ID, CLIENT_IP));

        assertThat(result.revokedCount()).isZero();
    }

    @Test
    void should_throw_RateLimitedException_when_account_throttled() {
        doThrow(new RateLimitedException("logout-all:" + ACCOUNT_ID.value(), Duration.ofSeconds(60)))
                .when(rateLimitService)
                .consumeOrThrow(eq("logout-all:" + ACCOUNT_ID.value()), any());

        assertThatThrownBy(() -> useCase.execute(new LogoutAllSessionsCommand(ACCOUNT_ID, CLIENT_IP)))
                .isInstanceOf(RateLimitedException.class);

        verify(refreshTokenRepository, never()).revokeAllForAccount(any(), any());
    }

    @Test
    void should_throw_RateLimitedException_when_ip_throttled() {
        doThrow(new RateLimitedException("logout-all:" + CLIENT_IP, Duration.ofSeconds(60)))
                .when(rateLimitService)
                .consumeOrThrow(eq("logout-all:" + CLIENT_IP), any());

        assertThatThrownBy(() -> useCase.execute(new LogoutAllSessionsCommand(ACCOUNT_ID, CLIENT_IP)))
                .isInstanceOf(RateLimitedException.class);

        verify(refreshTokenRepository, never()).revokeAllForAccount(any(), any());
    }

    @Test
    void should_propagate_repository_exception() {
        when(refreshTokenRepository.revokeAllForAccount(any(), any())).thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> useCase.execute(new LogoutAllSessionsCommand(ACCOUNT_ID, CLIENT_IP)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB down");
    }
}
