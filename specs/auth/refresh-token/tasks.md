# Implementation Tasks: Refresh Token

**Use case**: `refresh-token`
**Spec**: [`./spec.md`](./spec.md)
**Plan**: [`./plan.md`](./plan.md)
**Phase**: M1.2 Phase 1.3（auth 会话生命周期 — rotate-on-use refresh token）
**Status**: ✅ Implemented（PR [#101](https://github.com/xiaocaishen-michael/my-beloved-server/pull/101) squash-merged at commit `1b33f7e`，与 logout-all 一并合入）
**Estimated total**: ~12-14h（domain + infra + retrofit 3 既有 UseCase + E2E + concurrency；显著高于 1.1 / 1.2 因新表 + 三处回填）

## 实施记录

T0-T13 全部已交付，与 logout-all（Phase 1.4）合并由 PR [#101](https://github.com/xiaocaishen-michael/my-beloved-server/pull/101) 一次性 squash-merge 进 main（merge commit `1b33f7e`）。

**与原 plan/tasks 的偏离**：

- 原 PR #100（`feat(auth): impl refresh-token`）单独提交后 CLOSED 未 merge，原因是 logout-all 在 Phase 1.4 做完后顺手与 refresh-token 合并提交（PR #101），减少双 PR review 编排开销。下方 "Phasing PR 拆分" 段落写的"PR 1 = docs / PR 2 = refresh-token impl 单独 ship"假设性方案 **未实际采用**。
- T0 Migration 文件命名 `V4__create_refresh_token_table.sql` 在 impl 时改为 `V5__create_refresh_token_table.sql`——V4 在 Phase 1.1/1.2 已被 `V4__add_account_last_login_at.sql` 占用（Flyway 维持 shared / + account/ 全局单调递增的迁移历史）。文件头 SQL 注释已就 V5 vs V4 的偏离做完整说明。

实施过程的完整变更明细见 PR #101 描述与 merge commit `1b33f7e`（含原 sub-commit 的 commit message 全文）。

下文段落保留原 TDD 节奏 / 任务依赖 / 测试矩阵设计意图作为 reference（同模式 use case 可参考）。

> **TDD 节奏**：每个 task 内严格红绿循环。任务标签：`[Migration]` / `[Domain]` / `[Infra]` / `[App]` / `[Web]` / `[Retrofit]` / `[E2E]` / `[Concurrency]` / `[Contract]`。

## Critical Path（按依赖顺序）

### T0 [Migration] V4__create_refresh_token_table.sql

**File**: `mbw-account/src/main/resources/db/migration/account/V4__create_refresh_token_table.sql`

**Logic**: per `plan.md` § Migration 完整 SQL：

- `CREATE TABLE account.refresh_token` (id / token_hash VARCHAR(64) NOT NULL / account_id BIGINT NOT NULL / expires_at / revoked_at NULL / created_at default now())
- `CREATE UNIQUE INDEX uk_refresh_token_token_hash` on `(token_hash)`
- `CREATE INDEX idx_refresh_token_account_id_active` on `(account_id) WHERE revoked_at IS NULL` （partial）

**Test**: 本地起 PG container 跑 Flyway，断言：

- `account.refresh_token` 表存在
- 两个索引创建成功（`SELECT indexname FROM pg_indexes WHERE schemaname='account' AND tablename='refresh_token'`）
- partial index 谓词正确（`SELECT pg_get_indexdef('idx_refresh_token_account_id_active'::regclass)` 含 `WHERE revoked_at IS NULL`）

**Dependencies**: 无。可与 T1-T4 并行。

---

### T1 [Domain] RefreshTokenHash 值对象

**File**: `mbw-account/src/main/java/com/mbw/account/domain/model/RefreshTokenHash.java`

**Logic**: `record RefreshTokenHash(String value)` + 构造器校验：

- non-null + non-blank
- 长度恰为 64 hex chars（SHA-256 hex 输出）
- 仅含 `[0-9a-f]`

**Test**: `RefreshTokenHashTest`（**新建**）

- `should_accept_valid_64_char_lowercase_hex()`
- `should_reject_when_value_is_null()`
- `should_reject_when_value_is_blank()`
- `should_reject_when_value_length_not_64()`
- `should_reject_when_value_contains_uppercase()`（确保规范化下游不触发 mismatch）
- `should_reject_when_value_contains_non_hex_chars()`

**Dependencies**: 无。可与 T0/T2/T3/T4 并行。

---

### T2 [Domain] RefreshTokenRecord 聚合根

**File**: `mbw-account/src/main/java/com/mbw/account/domain/model/RefreshTokenRecord.java`

**Logic**: per `plan.md` § Domain Design：immutable class（field-final + with-style 重建）；

- factory `createActive(hash, accountId, expiresAt, now)` → 返回 `id=null`、`revokedAt=null`、`createdAt=now`
- `revoke(now)` → 已 revoke 抛 `IllegalStateException`；否则返新实例 with `revokedAt=now`
- `isActive(now)` → `revokedAt == null && expiresAt.isAfter(now)`

**Test**: `RefreshTokenRecordTest`（**新建**）

- `should_create_active_record_with_revokedAt_null_and_id_null()`
- `should_set_revokedAt_when_revoke_called()`
- `should_throw_when_revoke_called_twice()`
- `should_return_true_isActive_when_not_revoked_and_not_expired()`
- `should_return_false_isActive_when_revoked()`
- `should_return_false_isActive_when_expired()`

**Dependencies**: T1（RefreshTokenHash）。可与 T0/T3/T4 并行。

---

### T3 [Domain] RefreshTokenHasher domain service

**File**: `mbw-account/src/main/java/com/mbw/account/domain/service/RefreshTokenHasher.java`

**Logic**: SHA-256 → lowercase hex（`HexFormat.of().formatHex(...)`），返回 `RefreshTokenHash`。

**Test**: `RefreshTokenHasherTest`（**新建**）

- `should_produce_64_char_lowercase_hex()`
- `should_be_deterministic_for_same_input()` — 同 input 多次 hash 字节级一致
- `should_produce_distinct_hashes_for_distinct_inputs()`
- `should_match_known_sha256_vector()` — 用 RFC 标准向量验证（如 `SHA256("abc") = ba7816bf...`）

**Dependencies**: T1。可与 T0/T2 并行。

---

### T4 [Domain] RefreshTokenRepository 接口

**File**: `mbw-account/src/main/java/com/mbw/account/domain/repository/RefreshTokenRepository.java`

**Logic**: per `plan.md` 接口定义：

```java
RefreshTokenRecord save(RefreshTokenRecord record);
Optional<RefreshTokenRecord> findByTokenHash(RefreshTokenHash hash);
void revoke(RefreshTokenRecordId id, Instant revokedAt);
int revokeAllForAccount(AccountId accountId, Instant revokedAt);  // Phase 1.4 logout-all 用
```

**Test**: 接口无测；T5 实现层测。

**Dependencies**: T1, T2。可与 T0/T3 并行。

---

### T5 [Infra] RefreshTokenJpaEntity + Mapper + RepositoryImpl

**Files**:

- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/RefreshTokenJpaEntity.java`
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/RefreshTokenJpaRepository.java`
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/RefreshTokenMapper.java`
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/RefreshTokenRepositoryImpl.java`

**Logic**:

- JPA Entity：`@Table(schema = "account", name = "refresh_token")` + 字段映射 + `@Column(name = "token_hash", unique = true)`
- JpaRepository extends `JpaRepository<RefreshTokenJpaEntity, Long>` + `Optional<RefreshTokenJpaEntity> findByTokenHash(String hash)`
- MapStruct mapper：domain ↔ JPA 双向
- RepositoryImpl：
  - `save` → JPA save + map back
  - `findByTokenHash` → JPA findByTokenHash + map
  - `revoke` → 自定义 `@Modifying @Query("UPDATE ... SET revokedAt = :now WHERE id = :id AND revokedAt IS NULL")`（双重保险）
  - `revokeAllForAccount` → `@Modifying @Query("UPDATE ... SET revokedAt = :now WHERE accountId = :id AND revokedAt IS NULL")` 返回 affected rows

**Test**: `RefreshTokenRepositoryImplIT`（**新建**），Testcontainers PG。

- `should_save_and_findByTokenHash_round_trip()`
- `should_return_empty_when_token_hash_not_found()`
- `should_revoke_active_record_set_revokedAt()`
- `should_be_noop_revoke_when_already_revoked()`（双重保险断言）
- `should_throw_when_save_duplicate_token_hash()`（UNIQUE 索引验证）
- `should_revokeAllForAccount_only_revokes_active_records()`
- `should_revokeAllForAccount_return_affected_count()`
- `should_revokeAllForAccount_use_partial_index()` — 通过 `EXPLAIN` 输出含 `idx_refresh_token_account_id_active` 验证

**Dependencies**: T0（migration）+ T2（domain entity）+ T4（接口）。

---

### T6 [App] RefreshTokenCommand + RefreshTokenResult

**Files**:

- `mbw-account/src/main/java/com/mbw/account/application/command/RefreshTokenCommand.java`
- `mbw-account/src/main/java/com/mbw/account/application/result/RefreshTokenResult.java`

**Logic**:

- `RefreshTokenCommand`: `record(String rawRefreshToken, String clientIp)`
- `RefreshTokenResult`: `record(long accountId, String accessToken, String refreshToken)`

**Test**: 纯 record，无逻辑；TDD 例外。

**Dependencies**: 无。可与 T0-T5 并行。

---

### T7 [App] RefreshTokenUseCase

**File**: `mbw-account/src/main/java/com/mbw/account/application/usecase/RefreshTokenUseCase.java`

**Logic**: per `plan.md` § UseCase 段，4 步执行链 + `@Transactional(rollbackFor = Throwable.class)`：

1. 限流（`refresh:<ip>` + `refresh:<token_hash>`）
2. hash input + 查记录 + 校验 isActive
3. 校验关联 Account 存在 + ACTIVE
4. rotate：签新 access + 新 refresh + 写新 record + revoke 旧 record

**Test**: `RefreshTokenUseCaseTest`（**新建**），Mockito mock 6 依赖（RateLimitService / RefreshTokenHasher / RefreshTokenRepository / AccountRepository / TokenIssuer / AccountStateMachine）。

**8 分支覆盖（红绿循环逐条）**：

- `should_return_new_token_pair_when_refresh_token_valid()` — happy path（断言旧 record revoked + 新 record saved + 新 token 返回）
- `should_throw_RateLimitedException_when_ip_throttled()`
- `should_throw_RateLimitedException_when_token_hash_throttled()`
- `should_throw_InvalidCredentialsException_when_token_not_found_in_db()` — 攻击者构造 / 1.3 前签的 token
- `should_throw_InvalidCredentialsException_when_token_expired()` — `expiresAt < now`
- `should_throw_InvalidCredentialsException_when_token_already_revoked()` — `revokedAt != null`（含场景：旧 token 重放）
- `should_throw_InvalidCredentialsException_when_account_not_found()` — Account 被删
- `should_throw_InvalidCredentialsException_when_account_FROZEN()` — 状态机
- `should_rollback_when_save_new_record_fails()` — 写新失败 → 旧未 revoke + 新未持久化
- `should_rollback_when_revoke_old_record_fails()` — revoke 失败 → 新已写但事务回滚

**Dependencies**: T1-T6 完成。

---

### T8 [Web] RefreshTokenRequest + AuthController.refreshToken

**Files**:

- `mbw-account/src/main/java/com/mbw/account/web/request/RefreshTokenRequest.java`
- `mbw-account/src/main/java/com/mbw/account/web/controller/AuthController.java`（**1.1 已建**，扩展 method）

**Logic**:

- `RefreshTokenRequest`: record(`@NotBlank String refreshToken`) + `toCommand(clientIp)` 方法
- AuthController 加 `@PostMapping("/refresh-token")` 方法：解 IP（`HttpServletRequest.getRemoteAddr()`）+ 调 UseCase + 返 LoginResponse（复用 1.1 的 response，字段一致）
- Springdoc 注解齐（含 OpenAPI 描述 + 错误码 examples）

**Test**: `AuthControllerRefreshTokenIT`（**新建**），`@WebMvcTest(AuthController.class)`：

- `should_return_200_when_valid_request()` — happy path
- `should_return_400_when_refreshToken_blank_or_missing()` — Validation
- `should_return_401_when_InvalidCredentialsException_thrown()` — 不存在 / 过期 / revoked / FROZEN
- `should_return_429_when_RateLimitedException_thrown()` — 限流
- `should_return_500_when_unexpected_exception()` — 兜底

**Dependencies**: T7 完成。

---

### T9 [Retrofit] register-by-phone / login-by-phone-sms / login-by-password 三 UseCase 接入 RefreshTokenRecord 持久化

**Files**:

- `mbw-account/src/main/java/com/mbw/account/application/usecase/RegisterByPhoneUseCase.java`（既有，扩展）
- `mbw-account/src/main/java/com/mbw/account/application/usecase/LoginByPhoneSmsUseCase.java`（1.1 完成后扩展）
- `mbw-account/src/main/java/com/mbw/account/application/usecase/LoginByPasswordUseCase.java`（1.2 完成后扩展）

**Logic**: 三处统一改动，per spec.md FR-009：

```java
String refreshToken = tokenIssuer.signRefresh();
var record = RefreshTokenRecord.createActive(
    hasher.hash(refreshToken),
    account.id(),
    Instant.now().plus(Duration.ofDays(30)),
    Instant.now()
);
refreshTokenRepository.save(record);  // ← 新增此行（事务内，依赖既有 @Transactional 回滚）
```

**Test**: 既有 E2E 测试**扩展断言**（不新建测试类）：

- `RegisterByPhoneE2EIT`（既有）扩展：注册成功后 `SELECT FROM account.refresh_token WHERE account_id = ?` 必有 1 行（hash 非空 + revoked_at IS NULL + expires_at ≈ now+30d）
- `LoginByPhoneSmsE2EIT`（1.1 既有）扩展：同上
- `LoginByPasswordE2EIT`（1.2 既有）扩展：同上
- 新增：`should_rollback_register_when_refresh_token_record_save_fails()` — mock RefreshTokenRepository.save 抛异常 → Account 未持久化（事务整体回滚）

**Dependencies**: T7 完成 + 1.1 / 1.2 impl PR 已合并（即按 PR 顺序：1.1 impl → 1.2 impl → 1.3 docs（本 PR）→ 1.3 impl）。

---

### T10 [E2E] RefreshTokenE2EIT

**File**: `mbw-account/src/test/java/com/mbw/account/web/RefreshTokenE2EIT.java`（**新建**）

**Logic**: Testcontainers PG + Redis + WireMock (SMS gateway)。覆盖 spec.md 9 个 Acceptance Scenarios + SC-005（限流）+ SC-002（100 并发）。

**Test cases**：

- US1 (3 个 AS) + US2 (4 个 AS) + US3 (2 个 AS) = 9 个独立 scenarios
- US1 重点 AS：rotate 成功 + 旧 token revoke / 旧 token 重放 → 401 / 401 拦截器透明刷新
- US2 重点 AS：过期 / revoked / 签名错 / 不存在 → 4 场景**字节级一致响应**
- US3 重点 AS：IP 限流 / token hash 限流
- SC-002: 100 个不同 valid refresh token 并发 rotate，0 错 + rotation 数 == 请求数 + DB 内对应 100 个新 record + 100 个 revoked old record
- SC-005: FR-005 两条限流规则在集成测试中验证生效（IP 维度第 101 次 → 429；token hash 第 6 次 → 429）

**Fixture**: BeforeEach 起空 PG schema + 预注册 N 个 ACTIVE 账号 + 预签 N 个有效 refresh token；MockServer 拦截 SMS gateway 调用。

**Dependencies**: T0-T8 全部完成。

---

### T11 [Concurrency] RefreshTokenConcurrencyIT

**File**: `mbw-account/src/test/java/com/mbw/account/web/RefreshTokenConcurrencyIT.java`（**新建**）

**Logic**: per spec.md SC-003，同一 refresh token 10 并发 rotate → 仅 1 成功（DB 唯一约束 + revoked_at partial index 保证）。

**Test cases**：

- `should_only_one_succeed_when_same_token_rotated_10_times_concurrently()` — 10 线程同时调 refresh-token，断言：
  - 恰 1 个返回 200 + 新 token pair
  - 9 个返回 401 INVALID_CREDENTIALS
  - DB 内仅新增 1 个 record + 旧 record 仅 1 次 revoke 操作
- `should_handle_two_devices_racing_for_same_token()` — 2 线程模拟两设备同时拿同 token → 同上

**实现注**：用 `CountDownLatch` 同步起跑；UseCase 内 race condition 由 DB UNIQUE 约束 + `revoke ... WHERE revokedAt IS NULL` 双保险拦截（写新失败 → 事务回滚 → 老的依然有效）。

**Dependencies**: T0-T8 完成。可与 T10 并行。

---

### T12 [E2E] CrossUseCaseEnumerationDefenseIT 扩展

**File**: `mbw-account/src/test/java/com/mbw/account/web/CrossUseCaseEnumerationDefenseIT.java`（1.1 T13 已建，**扩展**）

**Logic**: 加 refresh-token 失效场景的 INVALID_CREDENTIALS 断言，与 1.1 / 1.2 既有 INVALID_CREDENTIALS 响应**字节级一致**。

**Test cases（新增）**：

- `should_have_byte_identical_response_refresh_expired_vs_revoked_vs_not_found_vs_signature_invalid()` — 4 个 refresh-token 失效场景断言响应（status / body / headers）字节级一致
- `should_have_byte_identical_response_refresh_invalid_vs_login_invalid_vs_register_already()` — 跨 4 个 use case 失败响应字节级一致

**Dependencies**: T10 完成。1.1 T13 已建测试类作为基础。

---

### T13 [Contract] OpenAPI spec 验证

**File**: 无新文件；本任务是验证 Springdoc 自动生成的 OpenAPI spec 含新 endpoint。

**Logic**:

- 起本地 Spring Boot：`./mvnw spring-boot:run -pl mbw-app`
- `curl http://localhost:8080/v3/api-docs > /tmp/spec.json`
- 断言：`/api/v1/auth/refresh-token` 路径存在 + request schema 正确 + response schema 与 LoginResponse 一致 + 401 错误响应描述含 INVALID_CREDENTIALS

**Test**: 手动验证 + 可选 `OpenApiSpecIT` 扩展（grep `/v3/api-docs` 输出含期望路径）。

**Dependencies**: T8 完成。

---

## Parallel Opportunities

- **T0 / T1 / T6 同起**（彼此无依赖）
- **T2, T3, T4 在 T1 完成后并行**
- **T5 在 T0 + T2 + T4 完成后**
- **T7 在 T1-T6 完成后**
- **T8 在 T7 完成后**
- **T9 在 T7 完成后**（独立 retrofit，可与 T8 并行）
- **T10 / T11 在 T0-T9 全完成后**（可并行跑测）
- **T12 在 T10 完成后**
- **T13 在 T8 完成后**（可与 T9-T12 并行）

## Definition of Done

- ✅ 所有 13 任务的 unit + IT 测试 RED → GREEN
- ✅ `./mvnw -pl mbw-account verify` 全绿（含 RefreshTokenE2EIT / RefreshTokenConcurrencyIT / 扩展的 3 个既有 E2E）
- ✅ `./mvnw -pl mbw-app -Dtest=ModuleStructureTest test` 全绿（ArchUnit）
- ✅ Spotless + Checkstyle 全绿（CI required check）
- ✅ OpenAPI spec 含 `/api/v1/auth/refresh-token`
- ✅ Cross-use-case enumeration defense 测试 GREEN（含 refresh-token 失效场景）
- ✅ Concurrency rotation 测试 GREEN（10 并发同 token 仅 1 成功）
- ✅ 三个既有 E2E 测试断言 RefreshTokenRecord 持久化生效（FR-009）

## Phasing PR 拆分

按 [meta plan file 拆 PR 顺序](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/plans/sdd-github-spec-kit-https-github-com-gi-drifting-rossum.md) Phase 1 server 部分：

- **PR 1（本 PR）**: `docs(auth): refresh-token spec + plan + tasks + analysis`（仅文档）
- **PR 2**: `feat(auth): impl refresh-token + RefreshTokenRecord 持久化`（T0-T13 全部代码 + 测试 + migration + retrofit 3 既有 UseCase）

PR 2 内部按依赖关系迭代提交（不强制 13 个 sub-PR），但 commit 历史应清晰展示 TDD 红绿循环 + retrofit 3 既有 UseCase 的"加一行 save + 加 assertion"步骤。
