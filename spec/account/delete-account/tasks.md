# Implementation Tasks: Delete Account

**Spec**: [`./spec.md`](./spec.md) · **Plan**: [`./plan.md`](./plan.md)
**Phase**: M1.3 Phase 1（账号生命周期 — ACTIVE → FROZEN 入口）
**Estimated total**: ~10-14h（2 migration + 2 domain extend + 1 event + 2 infra extend + 2 use case + 1 controller + 4 测试类）

> **TDD 节奏**：每条 task 严格红绿循环；测试任务绑定到实现 task，不独立列。任务标签：`[Migration]` / `[Domain]` / `[App]` / `[Infra]` / `[Web]` / `[E2E]` / `[Concurrency]` / `[Contract]`。

## Critical Path（按依赖顺序）

### T0 [Migration] V7 add `freeze_until` column to `account.account`

**File**: `mbw-account/src/main/resources/db/migration/account/V7__add_account_freeze_until.sql`

**Logic**: per `plan.md` § Migration V7：

- `ALTER TABLE account.account ADD COLUMN freeze_until TIMESTAMP WITH TIME ZONE NULL`
- 加 partial index `idx_account_freeze_until_active ON (freeze_until) WHERE status='FROZEN' AND freeze_until IS NOT NULL`（为后续 anonymize scheduler 准备）

**Test**: 本地起 PG container 跑 Flyway，断言：

- `account.account` 含 `freeze_until` 列 + nullable
- partial index 创建成功（`SELECT pg_get_indexdef('idx_account_freeze_until_active'::regclass)` 含两条 WHERE 谓词）

**Dependencies**: 无。可与 T1 并行。

---

### T1 [Migration] V8 add `purpose` column to `account.account_sms_code`

**File**: `mbw-account/src/main/resources/db/migration/account/V8__add_account_sms_code_purpose.sql`

**Logic**: per `plan.md` § Migration V8：

- `ALTER TABLE account.account_sms_code ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'PHONE_SMS_AUTH'` — DEFAULT 兜底既有行
- 加 composite partial index `idx_account_sms_code_account_purpose_active ON (account_id, purpose) WHERE used_at IS NULL`

**Test**: 同 T0 模式，断言：

- 列存在 + DEFAULT 生效（既有行 purpose='PHONE_SMS_AUTH'）
- 新写入行 purpose 必填
- partial index 创建成功

**Dependencies**: 无。可与 T0 并行。

---

### T2 [Domain] `Account.markFrozen` + `AccountStateMachine.markFrozen` + `AccountSmsCodePurpose.DELETE_ACCOUNT`

**Files**:

- `mbw-account/src/main/java/com/mbw/account/domain/model/Account.java`（**改**，加 freezeUntil + markFrozen）
- `mbw-account/src/main/java/com/mbw/account/domain/service/AccountStateMachine.java`（**改**，加 facade）
- `mbw-account/src/main/java/com/mbw/account/domain/model/AccountSmsCodePurpose.java`（**改**，加 enum 值）

**Logic**:

- `Account.markFrozen(Instant freezeUntil, Instant now)` package-private：校验 `status == ACTIVE` → 不满足抛 `IllegalAccountStateException("ACCOUNT_NOT_ACTIVE")`；满足则 `this.status = FROZEN; this.freezeUntil = freezeUntil; this.updatedAt = now;`
- `AccountStateMachine.markFrozen(Account, Instant freezeUntil, Instant now)` facade：调 `account.markFrozen(...)`
- `AccountSmsCodePurpose` enum 加 `DELETE_ACCOUNT`（已有 `PHONE_SMS_AUTH`）

**Test**:

- `AccountTest.should_transition_to_FROZEN_with_freezeUntil_when_markFrozen_called_on_ACTIVE()`
- `AccountTest.should_throw_IllegalAccountStateException_when_markFrozen_called_on_FROZEN()` （已 FROZEN 不可重复）
- `AccountTest.should_throw_when_markFrozen_called_on_ANONYMIZED()`
- `AccountStateMachineTest.should_delegate_markFrozen_to_account()` （Mockito spy）
- `AccountSmsCodePurposeTest.should_contain_DELETE_ACCOUNT_value()`（防误删 enum 值）

**Dependencies**: 无（domain 层纯改动）。可与 T0/T1/T3 并行。

---

### T3 [Domain] `AccountDeletionRequestedEvent` (api.event)

**File**: `mbw-account/src/main/java/com/mbw/account/api/event/AccountDeletionRequestedEvent.java`（**新建**）

**Logic**: record `(AccountId accountId, Instant freezeAt, Instant freezeUntil, Instant occurredAt)` — 放 `api.event` 包（per modular-strategy 跨模块事件契约）

**Test**: 纯 record，无逻辑；TDD 例外。但加一个 `AccountDeletionRequestedEventTest` 简单断言 `record` 字段访问 + 不可变（`equals` / `hashCode` / `toString` 自动生成行为）。

**Dependencies**: 无。可与 T0/T1/T2 并行。

---

### T4 [Infra] `AccountSmsCodeJpaEntity.purpose` + `JpaRepository.findFirst...` + `RepositoryImpl.findActiveByPurposeAndAccountId`

**Files**:

- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/AccountSmsCodeJpaEntity.java`（**改**）
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/AccountSmsCodeJpaRepository.java`（**改**）
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/AccountSmsCodeRepositoryImpl.java`（**改**）
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/AccountSmsCodeMapper.java`（**改**，purpose 双向映射）

**Logic**:

- JpaEntity：`@Enumerated(EnumType.STRING) @Column(name = "purpose", nullable = false, length = 32) private AccountSmsCodePurpose purpose;`
- JpaRepository：`Optional<AccountSmsCodeJpaEntity> findFirstByAccountIdAndPurposeAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(Long accountId, AccountSmsCodePurpose purpose, Instant now);`
- RepositoryImpl：`Optional<AccountSmsCode> findActiveByPurposeAndAccountId(AccountSmsCodePurpose purpose, AccountId accountId, Instant now)` → 调 JpaRepo + map
- Mapper：扩展 purpose 字段双向

**Test**: `AccountSmsCodeRepositoryImplIT`（**扩展**），Testcontainers PG。

- `should_findActiveByPurpose_return_active_record_when_DELETE_ACCOUNT_code_exists()`
- `should_findActiveByPurpose_return_empty_when_only_PHONE_SMS_AUTH_exists()`（purpose 物理隔离断言）
- `should_findActiveByPurpose_exclude_used_records()`
- `should_findActiveByPurpose_exclude_expired_records()`
- `should_save_and_round_trip_purpose_field()`

**Dependencies**: T1（migration 提供 purpose 列） + T2（enum DELETE_ACCOUNT 值）。

---

### T5 [Infra] `AccountJpaEntity.freezeUntil` + `AccountMapper`

**Files**:

- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/AccountJpaEntity.java`（**改**）
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/AccountMapper.java`（**改**）

**Logic**: `@Column(name = "freeze_until") private Instant freezeUntil;` + getter/setter + Mapper 双向（注意 `truncatedTo(MICROS)` per 已知 PG 精度坑，参 memory `feedback_pg_timestamptz_truncate_micros`）

**Test**: `AccountRepositoryImplIT`（**扩展**），Testcontainers PG。

- `should_persist_and_restore_freezeUntil_with_microsecond_precision()` — Instant.now().truncatedTo(MICROS) 写 → 读出字节级一致
- `should_persist_account_with_null_freezeUntil_when_status_ACTIVE()`

**Dependencies**: T0（migration 提供 freeze_until 列） + T2（Account.markFrozen 行为）。

---

### T6 [App] `SendDeletionCodeCommand` + `DeleteAccountCommand`

**Files**:

- `mbw-account/src/main/java/com/mbw/account/application/command/SendDeletionCodeCommand.java`（**新建**）
- `mbw-account/src/main/java/com/mbw/account/application/command/DeleteAccountCommand.java`（**新建**）

**Logic**:

- `SendDeletionCodeCommand(AccountId accountId, String clientIp)` record
- `DeleteAccountCommand(AccountId accountId, String code, String clientIp)` record

**Test**: 纯 record，TDD 例外。

**Dependencies**: 无。可与 T0-T5 并行。

---

### T7 [App] `SendDeletionCodeUseCase`

**File**: `mbw-account/src/main/java/com/mbw/account/application/usecase/SendDeletionCodeUseCase.java`（**新建**）

**Logic**: per `plan.md` § Endpoint 1 流程 — 9 步：限流 ×2 / findById / status 校验 / generate code+hash / persist / SmsClient.send / log。

**Test**: `SendDeletionCodeUseCaseTest`（**新建**），Mockito mock 5 依赖（RateLimitService / AccountRepository / AccountSmsCodeRepository / SmsClient / Clock）。

**6 分支覆盖**：

- `should_send_code_and_persist_when_account_is_ACTIVE()` — happy path
- `should_throw_RateLimitedException_when_account_throttled()` — account 维度限流
- `should_throw_RateLimitedException_when_ip_throttled()` — IP 维度限流
- `should_throw_AccountNotActiveException_when_account_FROZEN()` — status 校验
- `should_throw_AccountNotActiveException_when_account_ANONYMIZED()`
- `should_throw_SmsSendFailedException_when_SmsClient_throws()` — 短信发送失败

**Dependencies**: T2 + T4 + T6。

---

### T8 [App] `DeleteAccountUseCase`

**File**: `mbw-account/src/main/java/com/mbw/account/application/usecase/DeleteAccountUseCase.java`（**新建**）

**Logic**: per `plan.md` § Endpoint 2 流程，`@Transactional(rollbackFor = Throwable.class)` — 11 步：限流 ×2 / findActive code / hash 比对 / markUsed / findById / markFrozen / save / revokeAllForAccount / publishEvent / log。

**Test**: `DeleteAccountUseCaseTest`（**新建**），Mockito mock 7 依赖（RateLimitService / AccountSmsCodeRepository / AccountRepository / AccountStateMachine / RefreshTokenRepository / ApplicationEventPublisher / Clock）。

**10 分支覆盖**：

- `should_transition_to_FROZEN_and_revoke_tokens_and_publish_event_when_code_valid()` — happy path
- `should_throw_RateLimitedException_when_account_throttled()`
- `should_throw_RateLimitedException_when_ip_throttled()`
- `should_throw_InvalidDeletionCodeException_when_code_not_found()` — DB 无该 active code
- `should_throw_InvalidDeletionCodeException_when_code_hash_mismatch()` — code 错
- `should_throw_InvalidDeletionCodeException_when_code_expired()` — Repo 已过滤但二次校验防御
- `should_throw_InvalidDeletionCodeException_when_code_already_used()` — Repo 已过滤防御
- `should_throw_AccountNotActiveException_when_account_FROZEN()` — markFrozen 内 invariant 违反
- `should_throw_AccountNotFoundException_when_account_deleted_concurrently()` — 罕见 race
- `should_rollback_when_revokeAllForAccount_throws()` — 第 8 步异常 → 全部回滚（status / freezeUntil / code.usedAt 均不变）；断言 publishEvent 未调用

**Dependencies**: T2 + T3 + T4 + T5 + T6 + 1.4 logout-all 已落地（提供 `RefreshTokenRepository.revokeAllForAccount`）。

---

### T9 [Web] `DeleteAccountRequest` + `AccountDeletionController`（含两 endpoint）

**Files**:

- `mbw-account/src/main/java/com/mbw/account/web/request/DeleteAccountRequest.java`（**新建**）
- `mbw-account/src/main/java/com/mbw/account/web/controller/AccountDeletionController.java`（**新建**）

**Logic**:

- `DeleteAccountRequest(@NotBlank @Pattern("\\d{6}") String code)` record
- Controller per `plan.md` § Web layer 两 method
- Springdoc 注解齐：`@Operation` / `@SecurityRequirement(name = "bearerAuth")` / `@ApiResponse(responseCode = "204|401|429|503")` 等

**Test**: `AccountDeletionControllerTest`（**新建**），`@WebMvcTest(AccountDeletionController.class)` slice：

- 8 cases each endpoint：
  - 204 happy path（mock UseCase）
  - 400 body schema 错（缺 code / code 非 6 位）
  - 401 无 token / token 错
  - 429 限流（mock UseCase 抛 RateLimitedException）
  - 503 短信发送失败（mock UseCase 抛 SmsSendFailedException —— 仅 sendCode endpoint）
  - 500 兜底

**Dependencies**: T7 + T8。

---

### T10 [E2E] `AccountDeletionControllerE2EIT`

**File**: `mbw-account/src/test/java/com/mbw/account/web/AccountDeletionControllerE2EIT.java`（**新建**）

**Logic**: Testcontainers PG + Redis + WireMock SMS。覆盖 spec.md 9 个 Acceptance Scenarios + Edge Cases + SC-001/SC-002/SC-003/SC-004/SC-005/SC-006/SC-010。

**Test cases**：

- US1 (4 AS) + US2 (4 AS) + US3 (4 AS) + US4 (2 AS) = 14 个独立 scenarios
- US1 重点：完整双步 transition → DB status=FROZEN + freezeUntil + refresh_token revoked + outbox 1 条事件
- US2 重点：4 类 401 失败响应字节级一致
- US3 重点：错码 / 过期 / 已用 / 同 code 重复用都返同 401
- US4 重点：FR-005 五条限流规则均触发 429
- Edge：SMS 发送失败（WireMock 5xx） / 同账号双设备发码（第 2 次 429） / freeze_until 严格 = transition 时刻 + 15 days（容差 ≤ 1s）
- SC-010：transition 成功后 `event_publication` 表新增 1 条 `AccountDeletionRequestedEvent`（`completion_date` IS NULL）

**Fixture**: BeforeEach 起空 PG schema + 预注册 ACTIVE 账号 + 持有有效 access token + WireMock 短信 stub。

**Dependencies**: T0-T9 全部完成。

---

### T11 [Concurrency] `AccountDeletionConcurrencyIT`

**File**: `mbw-account/src/test/java/com/mbw/account/web/AccountDeletionConcurrencyIT.java`（**新建**）

**Logic**: per spec.md SC-007 + FR-006 原子性，并发场景验证。

**Test cases**：

- `should_only_one_succeed_when_same_code_submitted_5_times_concurrently()` — 5 线程同时提交同 code → 恰 1 个 204 + 4 个 401（DB 行锁 + markUsed UNIQUE 约束保证）
- `should_handle_two_devices_racing_for_deletion_codes_endpoint()` — 2 线程同时调 sendCode → 恰 1 个 200 + 1 个 429（限流时间窗内只过 1）
- `should_keep_all_state_unchanged_when_revokeAllForAccount_fails()` — Mockito spy 让 refreshTokenRepository.revokeAllForAccount 抛异常 → 断言 account.status 仍 ACTIVE + code.usedAt 仍 null + outbox 无新事件（FR-006 原子性）

**实现注**：用 `CountDownLatch` 同步起跑；spec SC-007 已含 freezeUntil 容差约束。

**Dependencies**: T0-T9 完成。可与 T10 并行。

---

### T12 [E2E] `CrossUseCaseEnumerationDefenseIT` 扩展

**File**: `mbw-account/src/test/java/com/mbw/account/web/CrossUseCaseEnumerationDefenseIT.java`（**1.1/1.3/1.4 已建，本次扩展**）

**Logic**: 加 deletion 4 类 401 响应字节级一致断言，与 register / login / refresh / logout-all 现有 INVALID_CREDENTIALS 响应字节级解耦的同时，与 deletion `INVALID_DELETION_CODE` 内部 4 路径字节级一致。

**Test cases（新增）**：

- `should_have_byte_identical_response_deletion_no_token_vs_token_invalid_vs_FROZEN_token_vs_ANONYMIZED_token()` — 4 类 401 路径
- `should_have_byte_identical_response_deletion_code_wrong_vs_expired_vs_used_vs_not_found()` — 4 类 INVALID_DELETION_CODE
- `should_have_distinct_problem_type_deletion_vs_login_invalid_credentials()` — 与 INVALID_CREDENTIALS 区分（不强制字节一致，但 problem.type 不同）

**Dependencies**: T10 完成。

---

### T13 [Contract] OpenAPI snapshot regen

**File**: `mbw-account/src/test/resources/api-docs.snapshot.json`（**改**）

**Logic**: 与 account-profile T9 同 pattern。起本地 Spring Boot → curl `/v3/api-docs` → 替换 snapshot 文件 → IT (`OpenApiSnapshotIT`) 跑通。

**Test**: 既有 `OpenApiSnapshotIT` 自动覆盖（断言 controller 推导出的 OpenAPI spec 与 snapshot 字节级一致）。

**Dependencies**: T9 完成。可与 T10/T11/T12 并行。

---

## Parallel Opportunities

- **T0 / T1 / T2 / T3 / T6 同起**（彼此无依赖）
- **T4 在 T1 + T2 完成后**
- **T5 在 T0 + T2 完成后**（可与 T4 并行）
- **T7 在 T2 + T4 + T6 完成后**
- **T8 在 T7 完成后**（共享 SMS code 持久化基础）
- **T9 在 T7 + T8 完成后**
- **T10 / T11 / T12 / T13 在 T0-T9 完成后**（4 个测试任务可全并行跑）

## Definition of Done

- ✅ 14 任务的 unit + IT 测试 RED → GREEN
- ✅ `./mvnw -pl mbw-account verify` 全绿（含新 IT + 扩展的 CrossUseCaseEnumerationDefenseIT + AccountSmsCodeRepositoryImplIT + AccountRepositoryImplIT）
- ✅ `./mvnw -pl mbw-app -Dtest=ModuleStructureTest test` 全绿（ArchUnit）
- ✅ Spotless + Checkstyle 全绿（CI required check）
- ✅ OpenAPI snapshot 含两 endpoint + bearerAuth 标记 + 401/429/503 错误响应描述
- ✅ Cross-use-case enumeration defense 测试 GREEN（含 deletion 4 类 401 字节级一致 + 与既有 INVALID_CREDENTIALS 路径解耦）
- ✅ Concurrency 测试 GREEN（5 并发同 code 仅 1 成功 + 2 并发 sendCode 仅 1 过限流）
- ✅ FR-006 原子性测试 GREEN（revokeAllForAccount 失败 → 全部回滚）
- ✅ outbox 写入测试 GREEN（transition 成功后 event_publication 新增 1 行）

## Phasing PR 拆分

按 SDD § 双阶段切分：

- **PR 1（本 PR，docs-only）**: `docs(account): delete-account spec + plan + tasks + analysis`（仅文档；触发 CI markdownlint + commitlint required check）
- **PR 2（impl，本 spec 范围外）**: `feat(account): impl delete-account (M1.3 / T0-T13)`（T0-T13 全部代码 + 测试 + 2 migration）

PR 2 内部按依赖关系迭代提交（不强制 14 个 sub-PR），但 commit 历史应清晰展示 TDD 红绿循环 + retrofit 既有 SMS code 基础设施的"加 purpose 维度 + 新建查询路径"步骤。

PR 2 须满足前置：

- 1.4 logout-all 已 ship（提供 `RefreshTokenRepository.revokeAllForAccount`）✅
- account-profile 已 ship（提供 `JwtAuthFilter` + status check 兜底）✅
