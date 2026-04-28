package com.mbw.app.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.shared.web.RateLimitService;
import com.mbw.shared.web.RateLimitedException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers Redis IT for {@link RateLimitService} bucket4j-redis backend.
 *
 * <p>Boots a real {@code redis:7-alpine} container, points Spring Data Redis
 * at it via {@code @DynamicPropertySource}, and exercises the production
 * code path (no mocks) to verify:
 *
 * <ul>
 *   <li>Token consumption and exhaustion across concurrent threads
 *   <li>Atomicity of the underlying CAS-based bucket4j ops
 *   <li>{@code reset} clears bucket state (next consume sees fresh capacity)
 * </ul>
 *
 * <p>JPA / DataSource auto-configuration is excluded — this test only
 * needs Redis + the rate-limit beans wired up.
 */
@SpringBootTest(classes = RateLimitServiceIT.TestApp.class)
@Testcontainers
class RateLimitServiceIT {

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private RateLimitService rateLimitService;

    private final Bandwidth fivePerMinute = Bandwidth.builder()
            .capacity(5)
            .refillIntervally(5, Duration.ofMinutes(1))
            .build();

    @Test
    void consumes_capacity_then_throws() {
        String key = "test:capacity:" + UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            rateLimitService.consumeOrThrow(key, fivePerMinute);
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rateLimitService.consumeOrThrow(key, fivePerMinute))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void atomic_under_concurrency_capacity_5_with_10_threads() throws InterruptedException {
        String key = "test:concurrent:" + UUID.randomUUID();
        int threadCount = 10;
        AtomicInteger consumed = new AtomicInteger();
        AtomicInteger throttled = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    rateLimitService.consumeOrThrow(key, fivePerMinute);
                    consumed.incrementAndGet();
                } catch (RateLimitedException e) {
                    throttled.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finished).as("all threads completed within 10s").isTrue();
        assertThat(consumed.get())
                .as("exactly capacity (5) succeeded under concurrency")
                .isEqualTo(5);
        assertThat(throttled.get())
                .as("remaining (10 - 5 = 5) were rate-limited")
                .isEqualTo(5);
    }

    @Test
    void reset_clears_bucket_state() {
        String key = "test:reset:" + UUID.randomUUID();
        // Drain the bucket
        for (int i = 0; i < 5; i++) {
            rateLimitService.consumeOrThrow(key, fivePerMinute);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rateLimitService.consumeOrThrow(key, fivePerMinute))
                .isInstanceOf(RateLimitedException.class);

        // Reset clears the bucket
        rateLimitService.reset(key);

        // Now consume should succeed again
        rateLimitService.consumeOrThrow(key, fivePerMinute);
    }

    /**
     * Minimal Spring Boot app used only by this IT — excludes JPA/DataSource
     * autoconfig (we don't need PostgreSQL for rate-limit tests). Restricts
     * component scanning to mbw-shared web (RateLimitService) and our
     * RateLimitConfig.
     */
    @SpringBootApplication(
            exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class,
                org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class
            },
            excludeName = {"org.springframework.modulith.events.jpa.JpaEventPublicationAutoConfiguration"})
    @ComponentScan(basePackageClasses = {RateLimitService.class})
    static class TestApp {

        /** Inlined ProxyManager bean (mirrors production RateLimitConfig)
         * to avoid cross-module classpath resolution issues with the Spring
         * Boot fat JAR's BOOT-INF/classes/ layout. */
        @Bean
        ProxyManager<String> rateLimitProxyManager(LettuceConnectionFactory connectionFactory) {
            Object nativeClient = connectionFactory.getNativeClient();
            if (!(nativeClient instanceof RedisClient redisClient)) {
                throw new IllegalStateException("Expected Lettuce RedisClient");
            }
            StatefulRedisConnection<String, byte[]> connection =
                    redisClient.connect(RedisCodec.of(StringCodec.UTF8, new ByteArrayValueCodec()));
            return LettuceBasedProxyManager.builderFor(connection)
                    .withExpirationStrategy(
                            ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(30)))
                    .build();
        }
    }

    private static final class ByteArrayValueCodec implements RedisCodec<String, byte[]> {
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
