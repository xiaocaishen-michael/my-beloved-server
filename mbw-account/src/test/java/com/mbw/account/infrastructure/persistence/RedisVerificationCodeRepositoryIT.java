package com.mbw.account.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.VerificationCodeRepository;
import com.mbw.account.domain.repository.VerificationCodeRepository.AttemptOutcome;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers Redis IT for {@link RedisVerificationCodeRepository}.
 *
 * <p>Boots a real {@code redis:7-alpine} container and exercises the
 * production code (no mocks) for both Lua scripts. Concurrency tests
 * verify atomicity properties that hand-rolled commands could not give:
 *
 * <ul>
 *   <li>{@code storeIfAbsent}: 10 concurrent writers for the same
 *       phone result in exactly 1 success + 9 false-returns
 *   <li>{@code incrementAttemptOrInvalidate}: 10 concurrent verifies
 *       with maxAttempts=10 produce exactly the set {1..10} of counts
 *       (no double-counting), and the threshold-crosser is the unique
 *       invalidator
 *   <li>{@code incrementAttemptOrInvalidate}: with maxAttempts=3 and
 *       10 concurrent verifies, exactly one thread crosses (count=3,
 *       invalidated=true) and post-DEL threads see count=0 +
 *       invalidated=true (the missing-key branch)
 * </ul>
 */
@SpringBootTest(classes = RedisVerificationCodeRepositoryIT.TestApp.class)
@Testcontainers
class RedisVerificationCodeRepositoryIT {

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private VerificationCodeRepository repo;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanup() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void storeIfAbsent_should_return_true_on_first_call_and_false_on_subsequent_with_same_key() {
        PhoneNumber phone = uniquePhone();

        boolean firstStore = repo.storeIfAbsent(phone, "$2a$08$hash1", Duration.ofMinutes(5));
        boolean secondStore = repo.storeIfAbsent(phone, "$2a$08$hash2", Duration.ofMinutes(5));

        assertThat(firstStore).isTrue();
        assertThat(secondStore).isFalse();

        Optional<String> stored = repo.findHashByPhone(phone);
        assertThat(stored).contains("$2a$08$hash1");
    }

    @Test
    void storeIfAbsent_should_serialize_concurrent_writes_to_one_winner() throws InterruptedException {
        PhoneNumber phone = uniquePhone();
        int threadCount = 10;
        var successes = new ConcurrentLinkedQueue<String>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            String hash = "$2a$08$hashFromThread" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (repo.storeIfAbsent(phone, hash, Duration.ofMinutes(5))) {
                        successes.add(hash);
                    }
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

        assertThat(finished).isTrue();
        assertThat(successes).hasSize(1);
        assertThat(repo.findHashByPhone(phone)).contains(successes.peek());
    }

    @Test
    void findHashByPhone_should_return_empty_for_unknown_phone() {
        assertThat(repo.findHashByPhone(uniquePhone())).isEmpty();
    }

    @Test
    void incrementAttemptOrInvalidate_should_count_atomically_under_max_threshold() throws InterruptedException {
        PhoneNumber phone = uniquePhone();
        repo.storeIfAbsent(phone, "$2a$08$hash", Duration.ofMinutes(5));
        int threadCount = 10;
        int maxAttempts = 10;
        var counts = new ConcurrentLinkedQueue<Integer>();
        var invalidations = new ConcurrentLinkedQueue<Integer>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    AttemptOutcome outcome = repo.incrementAttemptOrInvalidate(phone, maxAttempts);
                    counts.add(outcome.count());
                    if (outcome.invalidated()) {
                        invalidations.add(outcome.count());
                    }
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

        assertThat(finished).isTrue();
        assertThat(counts)
                .as("each concurrent increment is recorded uniquely (Lua atomicity)")
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(invalidations).as("only the threshold-crosser invalidates").containsExactly(maxAttempts);
        assertThat(repo.findHashByPhone(phone))
                .as("entry is gone after threshold crossing")
                .isEmpty();
    }

    @Test
    void incrementAttemptOrInvalidate_with_low_threshold_marks_post_DEL_attempts_invalidated()
            throws InterruptedException {
        PhoneNumber phone = uniquePhone();
        repo.storeIfAbsent(phone, "$2a$08$hash", Duration.ofMinutes(5));
        int threadCount = 10;
        int maxAttempts = 3;
        var allOutcomes = new ConcurrentLinkedQueue<AttemptOutcome>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    allOutcomes.add(repo.incrementAttemptOrInvalidate(phone, maxAttempts));
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

        assertThat(finished).isTrue();
        List<AttemptOutcome> outcomes = List.copyOf(allOutcomes);

        long thresholdCrossers = outcomes.stream()
                .filter(o -> o.invalidated() && o.count() == maxAttempts)
                .count();
        assertThat(thresholdCrossers)
                .as("exactly one thread crosses maxAttempts and triggers DEL")
                .isEqualTo(1);

        long preThresholdCounts = outcomes.stream()
                .filter(o -> !o.invalidated())
                .map(AttemptOutcome::count)
                .distinct()
                .count();
        assertThat(preThresholdCounts)
                .as("counts 1 and 2 each appear at least once")
                .isEqualTo(maxAttempts - 1L);

        long postDelete =
                outcomes.stream().filter(o -> o.invalidated() && o.count() == 0).count();
        assertThat(postDelete)
                .as("remaining 7 threads hit the missing-key branch with count=0 + invalidated=true")
                .isEqualTo(threadCount - maxAttempts);

        assertThat(repo.findHashByPhone(phone)).isEmpty();
    }

    @Test
    void delete_should_remove_the_entry() {
        PhoneNumber phone = uniquePhone();
        repo.storeIfAbsent(phone, "$2a$08$hash", Duration.ofMinutes(5));

        repo.delete(phone);

        assertThat(repo.findHashByPhone(phone)).isEmpty();
    }

    private static PhoneNumber uniquePhone() {
        long suffix = Math.abs(UUID.randomUUID().getMostSignificantBits()) % 10_000_000_000L;
        return new PhoneNumber("+8613" + String.format("%010d", suffix).substring(1));
    }

    /**
     * Minimal Spring Boot context — Redis-only. {@link Configuration} +
     * {@link EnableAutoConfiguration} (rather than
     * {@code @SpringBootApplication}) skips the default
     * {@code @ComponentScan} of this class's package — which would
     * otherwise pull in {@code AccountRepositoryImpl} and require JPA
     * wiring we deliberately exclude here. The Redis repository is
     * declared as an explicit {@code @Bean}.
     */
    @Configuration
    @EnableAutoConfiguration(
            exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class,
                org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class
            })
    static class TestApp {

        @Bean
        RedisVerificationCodeRepository redisVerificationCodeRepository(StringRedisTemplate redis) {
            return new RedisVerificationCodeRepository(redis);
        }
    }
}
