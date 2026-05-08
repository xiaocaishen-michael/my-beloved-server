-- Device-management spec (M1.X) — extend account.refresh_token with five
-- device-metadata columns so each refresh-token row stands in for one
-- "logged-in device" of its account (per spec FR-007 + plan.md § V11).
--
-- Per my-beloved-server CLAUDE.md § expand-migrate-contract: M3
-- internal-testing 前允许跳过三步法,在单 PR 内完成 expand + contract,
-- 条件 1 (无生产用户) + 条件 2 (本注释显式声明) 同时满足。M3 后任何
-- 破坏性 schema 变更必须三步走。
--
-- Index choice: list endpoint (GET /devices) walks the active rows of
-- a single account ordered by created_at DESC. The new partial index
-- covers (account_id, device_id) and filters revoked_at IS NULL, so
-- both the list query and the FR-005 "is current device" lookup walk
-- the same compact index. The existing
-- idx_refresh_token_account_id_active (V5) still serves logout-all's
-- bulk-revoke path.

ALTER TABLE account.refresh_token
    ADD COLUMN device_id    VARCHAR(36)  NULL,
    ADD COLUMN device_name  VARCHAR(64)  NULL,
    ADD COLUMN device_type  VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN ip_address   VARCHAR(45)  NULL,
    ADD COLUMN login_method VARCHAR(16)  NOT NULL DEFAULT 'PHONE_SMS';

COMMENT ON COLUMN account.refresh_token.device_id    IS 'Stable client-side UUID v4 (X-Device-Id header). Falls back to a server-generated UUID when the header is missing or malformed (per spec CL-001 (a)).';
COMMENT ON COLUMN account.refresh_token.device_name  IS 'Client-reported device label (e.g., "MK-iPhone"); NULL when client omits X-Device-Name header.';
COMMENT ON COLUMN account.refresh_token.device_type  IS 'Coarse-grained type used for UI icon selection: PHONE / TABLET / DESKTOP / WEB / UNKNOWN.';
COMMENT ON COLUMN account.refresh_token.ip_address   IS 'IP at row insert time (login or refresh). Private / loopback IPs are filtered to NULL pre-persist.';
COMMENT ON COLUMN account.refresh_token.login_method IS 'Login mechanism that issued this row. The /refresh-token rotation path inherits the parent row''s value (per FR-012) so the lineage of a device''s first login method is preserved across rotations.';

CREATE INDEX idx_refresh_token_account_device_active
    ON account.refresh_token (account_id, device_id)
    WHERE revoked_at IS NULL;
