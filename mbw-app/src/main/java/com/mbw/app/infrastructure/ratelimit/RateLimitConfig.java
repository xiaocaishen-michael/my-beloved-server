package com.mbw.app.infrastructure.ratelimit;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.nio.ByteBuffer;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Spring wiring for the bucket4j Redis proxy used by
 * {@link com.mbw.shared.web.RateLimitService} (ADR-0011 amended).
 *
 * <p>mbw-shared declares the {@link ProxyManager} dependency via
 * constructor injection but doesn't construct it itself — keeping
 * deployment-unit concerns (Redis client wiring) out of the shared
 * kernel. mbw-app, the deployment unit, owns this {@code @Configuration}.
 */
@Configuration
public class RateLimitConfig {

    /** Bucket state expires 30 min after the last token refill — long enough
     * that legitimate users in a 24-hour window keep their counter, short
     * enough that abandoned keys don't accumulate forever. */
    private static final Duration BUCKET_EXPIRATION = Duration.ofMinutes(30);

    /**
     * Bridges Spring Data Redis's Lettuce connection factory to a
     * bucket4j {@link LettuceBasedProxyManager}. We obtain the underlying
     * {@link RedisClient} via {@link LettuceConnectionFactory#getNativeClient()}
     * (Spring Data Redis 3.x exposes the native Lettuce client), open a
     * dedicated {@code String → byte[]} stateful connection for bucket4j,
     * and let the proxy manager handle key/state translation.
     *
     * <p>Connection lifecycle: the connection lives for the application
     * lifetime; bucket4j holds it for issuing CAS-based atomic operations.
     */
    @Bean
    public ProxyManager<String> rateLimitProxyManager(LettuceConnectionFactory connectionFactory) {
        Object nativeClient = connectionFactory.getNativeClient();
        if (!(nativeClient instanceof RedisClient redisClient)) {
            throw new IllegalStateException(
                    "Expected Lettuce RedisClient from LettuceConnectionFactory.getNativeClient(), got "
                            + (nativeClient == null
                                    ? "null"
                                    : nativeClient.getClass().getName()));
        }
        StatefulRedisConnection<String, byte[]> connection =
                redisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        return LettuceBasedProxyManager.builderFor(connection)
                .withExpirationStrategy(
                        io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
                                .basedOnTimeForRefillingBucketUpToMax(BUCKET_EXPIRATION))
                .build();
    }

    /** Lettuce codec for {@code byte[]} values (bucket4j stores binary state). */
    private static final class ByteArrayCodec implements RedisCodec<String, byte[]> {
        static final ByteArrayCodec INSTANCE = new ByteArrayCodec();

        @Override
        public String decodeKey(ByteBuffer bytes) {
            return StringCodec.UTF8.decodeKey(bytes);
        }

        @Override
        public byte[] decodeValue(ByteBuffer bytes) {
            byte[] arr = new byte[bytes.remaining()];
            bytes.get(arr);
            return arr;
        }

        @Override
        public ByteBuffer encodeKey(String key) {
            return StringCodec.UTF8.encodeKey(key);
        }

        @Override
        public ByteBuffer encodeValue(byte[] value) {
            return ByteBuffer.wrap(value);
        }
    }
}
