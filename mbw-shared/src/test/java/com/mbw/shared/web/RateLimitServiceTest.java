package com.mbw.shared.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import io.lettuce.core.RedisException;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private ProxyManager<String> proxyManager;

    @Mock
    private RemoteBucketBuilder<String> bucketBuilder;

    @Mock
    private BucketProxy bucketProxy;

    private RateLimitService rateLimitService;

    private final Bandwidth limit = Bandwidth.builder()
            .capacity(5)
            .refillIntervally(5, Duration.ofMinutes(1))
            .build();

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService(proxyManager);
    }

    @Test
    void consume_succeeds_when_bucket_has_tokens() {
        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(true);
        when(proxyManager.builder()).thenReturn(bucketBuilder);
        when(bucketBuilder.build(eq("sms:+8613800138000"), any(Supplier.class))).thenReturn(bucketProxy);
        when(bucketProxy.tryConsumeAndReturnRemaining(1L)).thenReturn(probe);

        rateLimitService.consumeOrThrow("sms:+8613800138000", limit);

        verify(bucketProxy).tryConsumeAndReturnRemaining(1L);
    }

    @Test
    void consume_throws_with_retry_after_when_bucket_exhausted() {
        long nanosToWait = Duration.ofSeconds(47).toNanos();
        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(false);
        when(probe.getNanosToWaitForRefill()).thenReturn(nanosToWait);
        when(proxyManager.builder()).thenReturn(bucketBuilder);
        when(bucketBuilder.build(eq("login:1.2.3.4"), any(Supplier.class))).thenReturn(bucketProxy);
        when(bucketProxy.tryConsumeAndReturnRemaining(1L)).thenReturn(probe);

        assertThatThrownBy(() -> rateLimitService.consumeOrThrow("login:1.2.3.4", limit))
                .isInstanceOf(RateLimitedException.class)
                .extracting("limitKey", "retryAfter")
                .containsExactly("login:1.2.3.4", Duration.ofNanos(nanosToWait));
    }

    @Test
    void consume_fails_closed_when_redis_unavailable() {
        when(proxyManager.builder()).thenThrow(new RedisException("connection refused"));

        assertThatThrownBy(() -> rateLimitService.consumeOrThrow("sms:+8613800138000", limit))
                .isInstanceOf(RateLimitedException.class)
                .extracting("limitKey", "retryAfter")
                .containsExactly("sms:+8613800138000", Duration.ZERO);
    }

    @Test
    void consume_fails_closed_when_bucket_consume_throws() {
        when(proxyManager.builder()).thenReturn(bucketBuilder);
        when(bucketBuilder.build(eq("sms:+8613800138000"), any(Supplier.class))).thenReturn(bucketProxy);
        when(bucketProxy.tryConsumeAndReturnRemaining(anyLong())).thenThrow(new RedisException("io error"));

        assertThatThrownBy(() -> rateLimitService.consumeOrThrow("sms:+8613800138000", limit))
                .isInstanceOf(RateLimitedException.class)
                .extracting("retryAfter")
                .isEqualTo(Duration.ZERO);
    }

    @Test
    void reset_removes_proxy() {
        rateLimitService.reset("sms:+8613800138000");
        verify(proxyManager, times(1)).removeProxy("sms:+8613800138000");
    }

    @Test
    void reset_swallows_redis_exception() {
        org.mockito.Mockito.doThrow(new RedisException("io error"))
                .when(proxyManager)
                .removeProxy("sms:+8613800138000");

        // Should not throw
        rateLimitService.reset("sms:+8613800138000");
        verify(proxyManager).removeProxy("sms:+8613800138000");
    }
}
