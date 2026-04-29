-- Atomically increment the failed-attempt counter; if the new count reaches
-- maxAttempts, delete the entry (invalidating the code) within the same
-- round-trip. Returns {count, invalidated} where:
--   count       = post-increment failure count, or 0 if the key did not exist
--   invalidated = 1 if the key was deleted (now or previously), 0 otherwise
--
-- KEYS[1]  = sms_code:<phone>
-- ARGV[1]  = max attempts threshold

if redis.call('EXISTS', KEYS[1]) == 0 then
    return {0, 1}
end

local count = redis.call('HINCRBY', KEYS[1], 'attemptCount', 1)
local maxAttempts = tonumber(ARGV[1])

if count >= maxAttempts then
    redis.call('DEL', KEYS[1])
    return {count, 1}
end

return {count, 0}
