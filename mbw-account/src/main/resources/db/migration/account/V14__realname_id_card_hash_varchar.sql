-- V14 — align account.realname_profile.id_card_hash column type with the
-- rest of the schema's hash columns (token_hash, code_hash, previous_phone_hash
-- are all VARCHAR(64)).
--
-- V12 created id_card_hash as CHAR(64). PG resolves CHAR to bpchar (Types#CHAR),
-- but Hibernate maps Java String to Types#VARCHAR by default — schema-validation
-- on boot fails with "wrong column type encountered ... found [bpchar
-- (Types#CHAR)], but expecting [char(64) (Types#VARCHAR)]" preventing the
-- application from starting.
--
-- The companion entity change drops the @Column(columnDefinition = "char(64)")
-- override on RealnameProfileJpaEntity so Hibernate produces and validates
-- VARCHAR(64) — consistent with every other 64-hex hash column in the schema.
--
-- A sha256 hex hash is always exactly 64 characters; CHAR's space-padding
-- semantics offer no functional advantage and risk corrupting equality
-- comparisons against trimmed VARCHAR inputs from application code.
--
-- Per my-beloved-server CLAUDE.md § expand-migrate-contract: this is a
-- semantically-equivalent type change (data values pre-V14 were always exactly
-- 64 hex chars or NULL — no padding to strip), executed in single PR per the
-- M3 内部测试前 dev exception (no production users; declared here).

ALTER TABLE account.realname_profile
    ALTER COLUMN id_card_hash TYPE VARCHAR(64);
