# Implementation Tasks: Cancel Deletion

**Spec**: [`./spec.md`](./spec.md) · **Plan**: [`./plan.md`](./plan.md)
**Phase**: M1.3（账号生命周期 — FROZEN → ACTIVE 撤销）
**Estimated total**: ~6-8h（无新 migration + 1 enum 扩展 + 1 domain method + 1 event + 2 use case + 1 controller + 4 测试类）

> **TDD 节奏**：每条 task 严格红绿循环；测试任务绑定到实现 task。任务标签：`[Domain]` / `[App]` / `[Web]` / `[E2E]` / `[Concurrency]` / `[Contract]`。
>
> **前置依赖**：[`../delete-account/`](../delete-account/) 实施 PR 必须先 merge（提供 V7 freeze_until / V8 purpose enum / AccountSmsCodeRepository.findActiveByPurposeAndAccountId / AccountSmsCodePurpose enum 物理结构）；本 spec 仅扩展 `AccountSmsCodePurpose.CANCEL_DELETION` enum 值 + 新增 application + web 层。

## Critical Path（按依赖顺序）

### T0 ✅ [Domain] `Account.markActiveFromFrozen` + `AccountStateMachine.markActiveFromFrozen` + `AccountSmsCodePurpose.CANCEL_DELETION`

**Files**:

- `mbw-account/src/main/java/com/mbw/account/domain/model/Account.java`（**改**，加 markActiveFromFrozen）
- `mbw-account/src/main/java/com/mbw/account/domain/service/AccountStateMachine.java`（**改**，加 facade）
- `mbw-account/src/main/java/com/mbw/account/domain/model/AccountSmsCodePurpose.java`（**改**，加 enum 值）

**Logic**:

- `Account.markActiveFromFrozen(Instant now)` package-private：校验 `status == FROZEN && freezeUntil != null && freezeUntil.isAfter(now)` → 不满足抛 `IllegalAccountStateException("ACCOUNT_NOT_FROZEN_IN_GRACE")`；满足则 `this.status = ACTIVE; this.freezeUntil = null; this.updatedAt = now;`
- `AccountStateMachine.markActiveFromFrozen(Account, Instant now)` facade：调 `account.markActiveFromFrozen(now)`
- `AccountSmsCodePurpose` enum 加 `CANCEL_DELETION`（与 delete-account 已加 `DELETE_ACCOUNT` 同 enum 扩展）

**Test**:

- `AccountTest.should_transition_FROZEN_to_ACTIVE_clearing_freezeUntil_when_grace_not_expired()`
- `AccountTest.should_throw_when_markActiveFromFrozen_called_on_ACTIVE()` — 不可重复
- `AccountTest.should_throw_when_markActiveFromFrozen_called_on_ANONYMIZED()` — 终态不可逆
- `AccountTest.should_throw_when_markActiveFromFrozen_called_on_FROZEN_with_grace_expired()` — freeze_until <= now
- `AccountTest.should_throw_when_markActiveFromFrozen_called_on_FROZEN_with_freezeUntil_null()` — invariant 违反
- `AccountStateMachineTest.should_delegate_markActiveFromFrozen_to_account()` Mockito spy
- `AccountSmsCodePurposeTest.should_contain_CANCEL_DELETION_value()`

**Dependencies**: 无（domain 层纯改动）。可与 T1 并行。

---

### T1 ✅ [Domain] `AccountDeletionCancelledEvent` (api.event)

**File**: `mbw-account/src/main/java/com/mbw/account/api/event/AccountDeletionCancelledEvent.java`（**新建**）

**Logic**: record `(AccountId accountId, Instant cancelledAt, Instant occurredAt)`，放 `api.event` 包

**Test**: 简单 record 断言（同 delete-account T3）

**Dependencies**: 无。可与 T0 并行。

---

### T2 ✅ [App] `SendCancelDeletionCodeCommand` + `CancelDeletionCommand` + `CancelDeletionResult`

**Files**:

- `mbw-account/src/main/java/com/mbw/account/application/command/SendCancelDeletionCodeCommand.java`（**新建**）
- `mbw-account/src/main/java/com/mbw/account/application/command/CancelDeletionCommand.java`（**新建**）
- `mbw-account/src/main/java/com/mbw/account/application/result/CancelDeletionResult.java`（**新建**，复用 LoginResult schema）

**Logic**:

- `SendCancelDeletionCodeCommand(String phone, String clientIp)` record
- `CancelDeletionCommand(String phone, String code, String clientIp)` record
- `CancelDeletionResult(long accountId, String accessToken, String refreshToken)` record

**Test**: 纯 record，TDD 例外。

**Dependencies**: 无。可与 T0/T1 并行。

---

### T3 ✅ [App] `SendCancelDeletionCodeUseCase`

**File**: `mbw-account/src/main/java/com/mbw/account/application/usecase/SendCancelDeletionCodeUseCase.java`（**新建**）

**Logic**: per `plan.md` § Endpoint 1 流程 — 4 类 phone 分支（未注册 / ACTIVE / ANONYMIZED / FROZEN+grace 有效 / FROZEN+grace 已过期）；前 3 + 第 5 类 dummy hash + 不发短信；仅第 4 类真发。

**Test**: `SendCancelDeletionCodeUseCaseTest`（**新建**），Mockito mock 5 依赖（RateLimitService / AccountRepository / AccountSmsCodeRepository / SmsClient / Clock）。

**8 分支覆盖**：

- `should_send_code_when_account_FROZEN_in_grace()` — happy path
- `should_dummy_response_when_phone_not_found()` — 反枚举：返成功但 SmsClient 未调用 + sms_code 未持久化
- `should_dummy_response_when_account_ACTIVE()` — 同上
- `should_dummy_response_when_account_ANONYMIZED()` — 同上
- `should_dummy_response_when_account_FROZEN_grace_expired()` — 同上
- `should_throw_RateLimitedException_when_phone_throttled()`
- `should_throw_RateLimitedException_when_ip_throttled()`
- `should_throw_SmsSendFailedException_when_SmsClient_fails_on_FROZEN_match()`

**Dependencies**: T0 + T2。

---

### T4 ✅ [App] `CancelDeletionUseCase`

**File**: `mbw-account/src/main/java/com/mbw/account/application/usecase/CancelDeletionUseCase.java`（**新建**）

**Logic**: per `plan.md` § Endpoint 2 流程，`@Transactional(rollbackFor = Throwable.class)` — 11 步：限流 ×2 / findByPhone 4 类分支 / dummy 比对（非 FROZEN+grace 路径）/ findActiveByPurpose / hash 比对 / markUsed / markActiveFromFrozen / save / publishEvent / issue token / persist refresh_token。

**Test**: `CancelDeletionUseCaseTest`（**新建**），Mockito mock 8 依赖（RateLimitService / AccountRepository / AccountSmsCodeRepository / Hasher / AccountStateMachine / RefreshTokenRepository / TokenIssuer / ApplicationEventPublisher / Clock）。

**12 分支覆盖**：

- `should_transition_ACTIVE_and_issue_token_and_publish_event_when_FROZEN_grace_valid_and_code_correct()` — happy path
- `should_throw_RateLimited_when_phone_throttled()`
- `should_throw_RateLimited_when_ip_throttled()`
- `should_throw_InvalidCredentials_when_phone_not_found()` — dummy hash 后 401
- `should_throw_InvalidCredentials_when_account_ACTIVE()`
- `should_throw_InvalidCredentials_when_account_ANONYMIZED()`
- `should_throw_InvalidCredentials_when_account_FROZEN_grace_expired()` — scheduler 抢跑场景
- `should_throw_InvalidCredentials_when_no_active_CANCEL_DELETION_code()` — sms_code 不存在
- `should_throw_InvalidCredentials_when_code_hash_mismatch()`
- `should_throw_InvalidCredentials_when_code_already_used()` — Repo 已过滤防御
- `should_rollback_when_TokenIssuer_throws()` — 第 9 步异常 → status 仍 FROZEN + sms_code 不 markUsed + 无事件
- `should_rollback_when_refresh_token_persist_fails()` — 第 10 步异常 → 同上

**Dependencies**: T0 + T1 + T2 + delete-account T4 (AccountSmsCodeRepository.findActiveByPurposeAndAccountId)

---

### T5 ✅ [Web] `SendCancelDeletionCodeRequest` + `CancelDeletionRequest` + `CancelDeletionController`

**Files**:

- `mbw-account/src/main/java/com/mbw/account/web/request/SendCancelDeletionCodeRequest.java`（**新建**）
- `mbw-account/src/main/java/com/mbw/account/web/request/CancelDeletionRequest.java`（**新建**）
- `mbw-account/src/main/java/com/mbw/account/web/controller/CancelDeletionController.java`（**新建**）

**Logic**:

- `SendCancelDeletionCodeRequest(@NotBlank @Pattern("^\\+\\d{8,15}$") String phone)` record
- `CancelDeletionRequest(@NotBlank @Pattern phone, @NotBlank @Pattern("\\d{6}") String code)` record
- Controller per `plan.md` § Web layer 两 method
- 公开端点（无 `@SecurityRequirement`）；Springdoc 注解齐

**Test**: `CancelDeletionControllerTest`（**新建**），`@WebMvcTest(CancelDeletionController.class)`：

- 8 cases per endpoint：
  - 200 happy（mock UseCase）
  - 400 body schema 错（缺字段 / phone 格式错 / code 非 6 位）
  - 401 mock UseCase 抛 InvalidCredentialsException
  - 429 限流
  - 503 SMS 失败（仅 sendCode endpoint）
  - 500 兜底

**Dependencies**: T3 + T4。

---

### T6 ✅ [E2E] `CancelDeletionControllerE2EIT`

**File**: `mbw-account/src/test/java/com/mbw/account/web/CancelDeletionControllerE2EIT.java`（**新建**）

**Logic**: Testcontainers PG + Redis + WireMock SMS。覆盖 spec.md 14 个 Acceptance Scenarios + Edge Cases + SC-001/SC-002/SC-003/SC-004/SC-006/SC-007/SC-008。

**Test cases**：

- US1 (4 AS) + US2 (4 AS) + US3 (4 AS) + US4 (3 AS) = 15 个独立 scenarios
- US1 重点：完整双步 transition → DB status=ACTIVE + freezeUntil=NULL + sms_code.used_at != null + outbox 1 条事件 + 持新 access token 调 /me 200
- US2 重点：4 类 phone × sms-codes 200 一致 + 4 类 phone × cancel 401 字节级一致
- US3 重点：错码 / 过期 / 已用 全 401（与 phone 反枚举 401 字节级一致）
- US4 重点：freeze_until 已过期 → sms-codes 200 但不发短信 + cancel 401
- SC-008：scheduler race 模拟 — sms-codes 时 freeze_until > now，submit 时 freeze_until < now → submit 必返 401

**Fixture**: BeforeEach 起空 PG schema + 预设 4 类 phone 账号 + WireMock SMS stub。

**Dependencies**: T0-T5 完成 + delete-account 实施 PR 已 merge。

---

### T7 ✅ [Concurrency] `CancelDeletionConcurrencyIT`

**File**: `mbw-account/src/test/java/com/mbw/account/web/CancelDeletionConcurrencyIT.java`（**新建**）

**Logic**: per spec.md SC-007 + FR-006 原子性。

**Test cases**：

- `should_only_one_succeed_when_same_phone_code_submitted_5_times_concurrently()` — 5 线程同时 cancel → 仅 1 个 200 + 4 个 401（DB 行锁 + markUsed 单次约束）
- `should_keep_state_unchanged_when_TokenIssuer_fails_in_transaction()` — Mockito spy 让 TokenIssuer 抛异常 → 断言 status 仍 FROZEN + freezeUntil 不变 + sms_code.used_at 仍 null + 无新 refresh_token + outbox 无新事件
- `should_keep_state_unchanged_when_refresh_token_persist_fails()` — 同 pattern

**Dependencies**: T0-T5 完成。可与 T6 并行。

---

### T8 ✅ [E2E] `CrossUseCaseEnumerationDefenseIT` 扩展

**File**: `mbw-account/src/test/java/com/mbw/account/web/CrossUseCaseEnumerationDefenseIT.java`（**多 use case 已建，本次扩展**）

**Logic**: cancel-deletion 5 类 401 路径字节级一致 + 与 phone-sms-auth INVALID_CREDENTIALS / delete-account INVALID_DELETION_CODE 字节级解耦 vs problem.type 区分。

**Test cases（新增）**：

- `should_have_byte_identical_response_cancel_phone_not_found_vs_ACTIVE_vs_ANONYMIZED_vs_FROZEN_expired_vs_code_wrong()` — 5 类 401
- `should_have_distinct_problem_type_cancel_INVALID_CREDENTIALS_vs_phone_sms_auth_INVALID_CREDENTIALS()` — 跨 use case problem.type 不同（防意外耦合）

**Dependencies**: T6 完成。

---

### T9 ✅ [Contract] OpenAPI snapshot regen

**File**: `mbw-account/src/test/resources/api-docs.snapshot.json`（**改**）

**Logic**: 同 delete-account T13 pattern。

**Test**: 既有 `OpenApiSnapshotIT` 自动覆盖。

**Dependencies**: T5 完成。可与 T6/T7/T8 并行。

---

## Parallel Opportunities

- **T0 / T1 / T2 同起**
- **T3 在 T0 + T2 完成后**
- **T4 在 T0 + T1 + T2 完成后 + delete-account T4 已 merge**
- **T5 在 T3 + T4 完成后**
- **T6 / T7 / T8 / T9 在 T0-T5 完成后**（4 个测试任务可全并行跑）

## Definition of Done

- ✅ 10 任务的 unit + IT 测试 RED → GREEN
- ✅ `./mvnw -pl mbw-account verify` 全绿
- ✅ `./mvnw -pl mbw-app -Dtest=ModuleStructureTest test` 全绿（ArchUnit）
- ✅ Spotless + Checkstyle 全绿
- ✅ OpenAPI snapshot 含两 endpoint
- ✅ Cross-use-case enumeration defense 测试 GREEN
- ✅ Concurrency + 原子性测试 GREEN
- ✅ outbox 写入测试 GREEN（cancel transition 后 event_publication 新增 1 行 AccountDeletionCancelledEvent）

## Phasing PR 拆分

按 SDD § 双阶段切分：

- **PR 1（本 PR，docs-only）**: `docs(account): cancel-deletion spec + plan + tasks + analysis`
- **PR 2（impl，本 spec 范围外）**: `feat(account): impl cancel-deletion (M1.3 / T0-T9)`

PR 2 的前置依赖：

- `feat(account): impl delete-account (M1.3 / T0-T13)` 已 ship（提供 V7 + V8 migration + AccountSmsCodePurpose enum 物理结构 + AccountSmsCodeRepository.findActiveByPurposeAndAccountId）
- 1.3 refresh-token + 1.4 logout-all + account-profile 已 ship（既有依赖）
