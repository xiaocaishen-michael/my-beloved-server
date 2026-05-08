-- V13 — align account.refresh_token.device_id schema invariant with the
-- domain invariant. V11 added device_id as NULLABLE because pre-V11 sessions
-- already existed in the table; downstream domain code (DeviceId value object,
-- RefreshTokenMapper) however assumes the column is non-null and throws on
-- NULL — the resulting NPE surfaced as a 500 on GET /devices for any account
-- that had a session created before V11 rolled out.
--
-- This migration closes the gap in two steps:
--   (1) backfill any pre-V11 NULL row with a server-generated UUID v4 so the
--       row stays addressable by device-management endpoints (FR-001 list
--       presents it as "Unknown device"; the user can revoke it from the UI);
--   (2) tighten the column to NOT NULL so the schema rejects future NULLs at
--       the source — the fromHeaderOrFallback fallback (CL-001 (a)) already
--       guarantees non-null on every token-issuing path going forward.
--
-- gen_random_uuid() is core PG since 13 — no extension required.
--
-- Per my-beloved-server CLAUDE.md § expand-migrate-contract: M3
-- internal-testing 前允许跳过三步法,在单 PR 内完成 backfill + contract,
-- 条件 1 (无生产用户) + 条件 2 (本注释显式声明) 同时满足。M3 后任何
-- 破坏性 schema 变更必须三步走。

UPDATE account.refresh_token
   SET device_id = gen_random_uuid()::text
 WHERE device_id IS NULL;

ALTER TABLE account.refresh_token
    ALTER COLUMN device_id SET NOT NULL;
