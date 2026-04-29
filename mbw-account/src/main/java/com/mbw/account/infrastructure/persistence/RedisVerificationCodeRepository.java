package com.mbw.account.infrastructure.persistence;

import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.VerificationCodeRepository;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Redis-backed adapter for {@link VerificationCodeRepository}.
 *
 * <p>Storage shape (per {@code spec.md} FR-002): hash key
 * {@code sms_code:<phone>} with fields {@code codeHash},
 * {@code attemptCount}.
 *
 * <p>Two operations need atomicity beyond what plain commands give:
 *
 * <ul>
 *   <li>{@link #storeIfAbsent} — exists-check + HSET + EXPIRE in a
 *       single Lua call so concurrent send-code requests within the
 *       60s rate-limit window do not overwrite each other if the
 *       client retries (FR-006 belt-and-suspenders).
 *   <li>{@link #incrementAttemptOrInvalidate} — HINCRBY +
 *       threshold-check + DEL in a single Lua call so that under
 *       concurrent verify attempts the {@code maxAttempts} threshold
 *       is enforced exactly once (otherwise three threads could each
 *       see {@code attemptCount=2}, increment to 3, and only one
 *       triggers the DEL — leaving the others to bypass the limit).
 * </ul>
 *
 * <p>Lua scripts live as classpath resources in
 * {@code resources/scripts/redis/}; the {@link DefaultRedisScript}
 * wrappers cache the SHA1 after first {@code EVAL} so subsequent calls
 * use {@code EVALSHA} (one round-trip).
 */
@Repository
public class RedisVerificationCodeRepository implements VerificationCodeRepository {

    private static final String KEY_PREFIX = "sms_code:";
    private static final String FIELD_CODE_HASH = "codeHash";

    private final StringRedisTemplate redis;
    private final RedisScript<Long> storeIfAbsentScript;
    private final RedisScript<List> incrementOrInvalidateScript;

    public RedisVerificationCodeRepository(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis);
        this.storeIfAbsentScript =
                new DefaultRedisScript<>(loadScript("scripts/redis/store_if_absent.lua"), Long.class);
        this.incrementOrInvalidateScript =
                new DefaultRedisScript<>(loadScript("scripts/redis/increment_or_invalidate.lua"), List.class);
    }

    @Override
    public boolean storeIfAbsent(PhoneNumber phone, String codeHash, Duration ttl) {
        Long stored =
                redis.execute(storeIfAbsentScript, List.of(key(phone)), codeHash, String.valueOf(ttl.toSeconds()));
        return stored != null && stored == 1L;
    }

    @Override
    public Optional<String> findHashByPhone(PhoneNumber phone) {
        Object hash = redis.opsForHash().get(key(phone), FIELD_CODE_HASH);
        return Optional.ofNullable(hash).map(Object::toString);
    }

    @Override
    @SuppressWarnings("unchecked")
    public AttemptOutcome incrementAttemptOrInvalidate(PhoneNumber phone, int maxAttempts) {
        List<Long> result = (List<Long>)
                redis.execute(incrementOrInvalidateScript, List.of(key(phone)), String.valueOf(maxAttempts));
        if (result == null || result.size() != 2) {
            throw new IllegalStateException("Unexpected Lua script result: " + result);
        }
        int count = result.get(0).intValue();
        boolean invalidated = result.get(1) == 1L;
        return new AttemptOutcome(count, invalidated);
    }

    @Override
    public void delete(PhoneNumber phone) {
        redis.delete(key(phone));
    }

    private static String key(PhoneNumber phone) {
        return KEY_PREFIX + phone.e164();
    }

    private static String loadScript(String classpath) {
        try (var stream = new ClassPathResource(classpath).getInputStream()) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to load Lua script: " + classpath, e);
        }
    }
}
