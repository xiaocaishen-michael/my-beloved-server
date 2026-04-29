-- Atomically store a verification-code hash entry only if no entry exists for
-- the phone, then set TTL. Returns 1 if stored, 0 if an entry already existed.
--
-- KEYS[1]  = sms_code:<phone>
-- ARGV[1]  = bcrypt code hash
-- ARGV[2]  = ttl seconds

if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end

redis.call('HSET', KEYS[1], 'codeHash', ARGV[1], 'attemptCount', 0)
redis.call('EXPIRE', KEYS[1], ARGV[2])
return 1
