# Implementation Plan: Delete Account

**Spec**: [`./spec.md`](./spec.md) · **Tasks**: [`./tasks.md`](./tasks.md)
**Phase**: M1.3（账号生命周期闭环 — ACTIVE → FROZEN 入口）
**Created**: 2026-05-06

> 本 plan 仅覆盖 `delete-account` use case；配套：[`../cancel-deletion/plan.md`](../cancel-deletion/plan.md)（FROZEN → ACTIVE 撤销）；anonymize scheduler 单独 spec 后续。

## 架构层级与职责（DDD 五层）

```text
mbw-account/
├── api/
│   ├── service/                 — (此 use case 不暴露跨模块 service)
│   └── event/
│       └── AccountDeletionRequestedEvent.java       — 新建 (FR-008)
│
├── domain/
│   ├── model/
│   │   ├── Account.java                              — 改 (加 freezeUntil 字段 + markFrozen 行为)
│   │   ├── AccountStatus.java                        — 不改 (FROZEN 枚举值已有)
│   │   └── AccountSmsCodePurpose.java                — 改 (enum 加 DELETE_ACCOUNT 值)
│   └── service/
│       └── AccountStateMachine.java                  — 改 (加 markFrozen facade)
│
├── application/
│   ├── command/
│   │   ├── SendDeletionCodeCommand.java              — 新建
│   │   └── DeleteAccountCommand.java                 — 新建
│   ├── result/
│   │   └── (无;两 endpoint 均返 204 No Content)
│   └── usecase/
│       ├── SendDeletionCodeUseCase.java              — 新建
│       └── DeleteAccountUseCase.java                 — 新建
│
├── infrastructure/
│   └── persistence/
│       ├── AccountSmsCodeJpaEntity.java              — 改 (加 purpose 字段映射)
│       ├── AccountSmsCodeJpaRepository.java          — 改 (加 findActiveByPurposeAndAccountId 查询)
│       ├── AccountSmsCodeRepositoryImpl.java         — 改 (purpose-aware 适配)
│       ├── AccountJpaEntity.java                     — 改 (加 freezeUntil 字段映射)
│       ├── AccountMapper.java                        — 改 (freezeUntil 映射)
│       └── AccountRepositoryImpl.java                — 不改 (save / find 自动覆盖新字段)
│
└── web/
    ├── controller/
    │   └── AccountDeletionController.java             — 新建
    ├── request/
    │   └── DeleteAccountRequest.java                  — 新建
    └── exception/
        └── (复用既有 GlobalExceptionHandler)
```

## 核心 use case 流程

### Endpoint 1: `POST /api/v1/accounts/me/deletion-codes`

**目的**：发起注销流程，第一步——生成 SMS code 并发送到账号绑定 phone。

**入参**：HTTP header `Authorization: Bearer <access_token>`；无 request body。

**响应**：204 No Content（成功）/ 401 ProblemDetail（鉴权失败 / FROZEN）/ 429 ProblemDetail（限流）/ 503 ProblemDetail（短信发送失败）。

**流程**：

1. Web layer — `AccountDeletionController.sendCode()`：
   1. 从 `JwtAuthFilter` 解出 `AccountId` (request attribute) + `clientIp`
   2. 调 `SendDeletionCodeUseCase.execute(SendDeletionCodeCommand(accountId, clientIp))`
   3. 返 `ResponseEntity.noContent().build()`
2. Application layer — `SendDeletionCodeUseCase`：
   1. `RateLimitService.tryAcquire("delete-code:account:<accountId>", 60s, 1)` — 超限抛 `RateLimitedException`
   2. `RateLimitService.tryAcquire("delete-code:ip:<ip>", 60s, 5)` — 超限同上
   3. `AccountRepository.findById(accountId)` → 不存在抛 `AccountNotFoundException`（理论不可达，filter 已 status-check）
   4. 校验 `account.status == ACTIVE` → 非 ACTIVE 抛 `AccountNotActiveException`（401，反枚举吞）
   5. 生成 6 位数字 code（`SecureRandom`） + SHA-256 hex
   6. `AccountSmsCodeRepository.save(AccountSmsCode.create(accountId, hash, expiresAt=now+10min, purpose=DELETE_ACCOUNT))`
   7. `SmsClient.send(account.phone, "您的注销验证码：<code>，10 分钟内有效")` — 失败抛 `SmsSendFailedException`（503）
   8. log INFO `account.deletion-code.sent accountId=<id>`（不打 code 明文 / phone 明文）
3. Infrastructure: `SmsClient` 已生产就绪（per phone-sms-auth）；`AccountSmsCodeRepository` 加 purpose-aware 路径

### Endpoint 2: `POST /api/v1/accounts/me/deletion`

**目的**：提交注销验证码 → ACTIVE → FROZEN transition + revoke all refresh tokens + 发事件。

**入参**：HTTP header `Authorization: Bearer <access_token>`；request body `{code: string}`。

**响应**：204 No Content（成功）/ 401 ProblemDetail（鉴权 / FROZEN / 错码 / 过期 / 已用）/ 429 ProblemDetail（限流）/ 400 ProblemDetail（body schema 错）。

**流程**：

1. Web layer — `AccountDeletionController.delete(@Valid DeleteAccountRequest body)`：
   1. 从 `JwtAuthFilter` 解出 `AccountId` + `clientIp`
   2. 调 `DeleteAccountUseCase.execute(DeleteAccountCommand(accountId, code, clientIp))`
   3. 返 `ResponseEntity.noContent().build()`
2. Application layer — `DeleteAccountUseCase` (`@Transactional(rollbackFor = Throwable.class)`)：
   1. `RateLimitService.tryAcquire("delete-submit:account:<accountId>", 60s, 5)`
   2. `RateLimitService.tryAcquire("delete-submit:ip:<ip>", 60s, 10)`
   3. `AccountSmsCodeRepository.findActiveByPurposeAndAccountId(DELETE_ACCOUNT, accountId)` → 不存在 / 已用 / 过期 抛 `InvalidDeletionCodeException`
   4. 校验 `RefreshTokenHasher.hash(code)` 与 record.codeHash 字节级匹配 → 失败抛 `InvalidDeletionCodeException`（同 attempts++ 维度限流，但 attempts 计入由 application 层 +1）
   5. 标 code 已用：`accountSmsCodeRepository.markUsed(record.id, now)`
   6. `AccountRepository.findById(accountId)` → 不存在 抛 `AccountNotFoundException`
   7. `AccountStateMachine.markFrozen(account, freezeUntil=now+15days, now)` — 校验 status == ACTIVE → 转 FROZEN + 写 freezeUntil
   8. `AccountRepository.save(account)` — JPA persist freezeUntil + status
   9. `int revokedCount = refreshTokenRepository.revokeAllForAccount(accountId, now)` — 复用 logout-all 落地的方法
   10. `eventPublisher.publish(new AccountDeletionRequestedEvent(accountId, freezeAt=now, freezeUntil, occurredAt=now))` — Spring Modulith 写 outbox
   11. log INFO `account.deletion.requested accountId=<id> freezeUntil=<utc>` + `revokedRefreshTokens=<count>`
   - 事务任一步失败 → 全部回滚（status 仍 ACTIVE，refresh_token 仍 active，无事件，code 不标已用——下次重试用户体验 = 同 code 仍可用直到 expiry）
3. Infrastructure: 复用既有 `RefreshTokenRepository.revokeAllForAccount`（logout-all 1.4 已落地）

## 数据流（请求生命周期）

```text
Client (web/native)
  │
  │  POST /api/v1/accounts/me/deletion-codes
  │  Authorization: Bearer <access>
  ▼
JwtAuthFilter (per account-profile FR-009)
  │  - 验签 + 取 sub claim → AccountId
  │  - 查 DB 验 status == ACTIVE → 非 ACTIVE 401 (吞)
  │  - 注入 AccountId 为 request attribute
  ▼
AccountDeletionController.sendCode(HttpServletRequest)
  │  - 取 AccountId + clientIp
  ▼
SendDeletionCodeUseCase.execute(cmd)
  │  - RateLimit 双维度
  │  - 生成 code + hash + persist
  │  - SmsClient.send → 真实短信
  ▼
204 No Content

------ 用户收到 SMS code ------

Client
  │
  │  POST /api/v1/accounts/me/deletion
  │  Authorization: Bearer <access>
  │  Body: {"code": "123456"}
  ▼
JwtAuthFilter (同上)
  ▼
AccountDeletionController.delete(@Valid body, HttpServletRequest)
  ▼
DeleteAccountUseCase.execute(cmd) [TRANSACTIONAL]
  │  - RateLimit 双维度
  │  - findActiveByPurposeAndAccountId
  │  - hash(code).equals(record.codeHash)
  │  - markUsed(code)
  │  - markFrozen(account, freezeUntil=now+15d)
  │  - account.save (status=FROZEN + freezeUntil)
  │  - refreshTokenRepository.revokeAllForAccount(accountId, now)
  │  - eventPublisher.publish(AccountDeletionRequestedEvent)
  ▼
204 No Content
```

## 复用既有基础设施（不新增）

| 资产 | 来源 | 用途 |
|---|---|---|
| `JwtAuthFilter` | account-profile T5 (PR #127) | 取 AccountId + status check 拦截非 ACTIVE |
| `RateLimitService` | ADR-0011 + 既有 | 限流（5 个键） |
| `SmsClient` | phone-sms-auth (PR #98) | 阿里云短信发送 + Resilience4j retry |
| `AccountSmsCodeRepository` | phone-sms-auth (PR #98 / PR #118) | SMS code 持久化（扩展 purpose） |
| `RefreshTokenRepository.revokeAllForAccount` | refresh-token + logout-all (PR #101) | 批量 revoke refresh token |
| `Spring Modulith Event Publication Registry` | 已就绪（per modular-strategy） | outbox 持久化新事件 |
| `GlobalExceptionHandler` | mbw-shared 既有 | 异常 → ProblemDetail 映射 |
| `AccountStateMachine` | account-profile / 既有 | facade pattern (markActive/markLoggedIn/changeDisplayName)，加 markFrozen |

## 新建组件

### Domain layer

- `Account.markFrozen(Instant freezeUntil, Instant now)` — package-private，写 status=FROZEN + freezeUntil。invariant：当前 status 必须 ACTIVE（违反抛 `IllegalAccountStateException`）。
- `AccountStateMachine.markFrozen(Account, Instant freezeUntil, Instant now)` — facade，调 `account.markFrozen`。
- `AccountSmsCodePurpose` enum 加 `DELETE_ACCOUNT` 值（既有 `PHONE_SMS_AUTH`）。
- 新事件类 `mbw-account/api/event/AccountDeletionRequestedEvent.java`：

  ```java
  public record AccountDeletionRequestedEvent(
      AccountId accountId,
      Instant freezeAt,
      Instant freezeUntil,
      Instant occurredAt) {}
  ```

  放 `api.event` 包（per modular-strategy 跨模块事件契约），允被 `mbw-pkm` 等 future 模块通过 api 引用。

### Application layer

- `SendDeletionCodeCommand(AccountId accountId, String clientIp)` record
- `DeleteAccountCommand(AccountId accountId, String code, String clientIp)` record
- `SendDeletionCodeUseCase`：执行流见上节
- `DeleteAccountUseCase`：执行流见上节，`@Transactional(rollbackFor = Throwable.class)`

### Infrastructure layer

- `AccountSmsCodeJpaEntity`：加 `@Column(name = "purpose") @Enumerated(EnumType.STRING) private AccountSmsCodePurpose purpose`
- `AccountSmsCodeJpaRepository`：加方法 `Optional<AccountSmsCodeJpaEntity> findFirstByAccountIdAndPurposeAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(Long accountId, AccountSmsCodePurpose purpose, Instant now)`
- `AccountSmsCodeRepositoryImpl`：加 `findActiveByPurposeAndAccountId` 适配方法（domain 层调用入口）
- `AccountJpaEntity`：加 `@Column(name = "freeze_until") private Instant freezeUntil` + getter/setter
- `AccountMapper`：扩展 freezeUntil 双向映射

### Web layer

- `AccountDeletionController`：

  ```java
  @RestController
  @RequestMapping("/api/v1/accounts/me")
  public class AccountDeletionController {

      @PostMapping("/deletion-codes")
      public ResponseEntity<Void> sendCode(HttpServletRequest req) {
          var accountId = extractAccountId(req);
          var clientIp = ClientIpExtractor.from(req);
          sendDeletionCodeUseCase.execute(new SendDeletionCodeCommand(accountId, clientIp));
          return ResponseEntity.noContent().build();
      }

      @PostMapping("/deletion")
      public ResponseEntity<Void> delete(
              @Valid @RequestBody DeleteAccountRequest body, HttpServletRequest req) {
          var accountId = extractAccountId(req);
          var clientIp = ClientIpExtractor.from(req);
          deleteAccountUseCase.execute(
              new DeleteAccountCommand(accountId, body.code(), clientIp));
          return ResponseEntity.noContent().build();
      }
  }
  ```

- `DeleteAccountRequest(@NotBlank String code)` record（`@Pattern("\\d{6}")` 防止 application 层多余校验）

## 数据模型变更

### V7 migration: `account.account` 加 `freeze_until` 列

文件：`mbw-account/src/main/resources/db/migration/account/V7__add_account_freeze_until.sql`

```sql
-- delete-account use case (M1.3): freeze_until tracks 15-day grace period
-- between FROZEN transition and ANONYMIZED scheduled task.
-- NULL when account is ACTIVE; non-null only while FROZEN.
-- Scheduler-driven anonymize (separate use case) scans WHERE
-- status='FROZEN' AND freeze_until < now().
ALTER TABLE account.account
    ADD COLUMN freeze_until TIMESTAMP WITH TIME ZONE NULL;

COMMENT ON COLUMN account.account.freeze_until
    IS 'When the FROZEN account becomes eligible for anonymization. NULL while ACTIVE/ANONYMIZED.';

-- Partial index for the scheduler scan (separate use case will use it):
CREATE INDEX idx_account_freeze_until_active
    ON account.account (freeze_until)
    WHERE status = 'FROZEN' AND freeze_until IS NOT NULL;
```

### V8 migration: `account.account_sms_code` 加 `purpose` 列

文件：`mbw-account/src/main/resources/db/migration/account/V8__add_account_sms_code_purpose.sql`

```sql
-- Generalize account_sms_code (originally only PHONE_SMS_AUTH) to also
-- carry DELETE_ACCOUNT codes. Future use cases (e.g. CANCEL_DELETION,
-- RESET_PASSWORD) extend this enum.
ALTER TABLE account.account_sms_code
    ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'PHONE_SMS_AUTH';

COMMENT ON COLUMN account.account_sms_code.purpose
    IS 'Enum: PHONE_SMS_AUTH | DELETE_ACCOUNT | CANCEL_DELETION (M1.3+).';

-- Composite index supporting findFirstByAccountIdAndPurposeAndUsedAtIsNull...
CREATE INDEX idx_account_sms_code_account_purpose_active
    ON account.account_sms_code (account_id, purpose)
    WHERE used_at IS NULL;
```

### Account JPA Entity 改

```java
// AccountJpaEntity.java additions
@Column(name = "freeze_until")
private Instant freezeUntil;
// + getter/setter
```

### AccountSmsCode JPA Entity 改

```java
// AccountSmsCodeJpaEntity.java additions
@Enumerated(EnumType.STRING)
@Column(name = "purpose", nullable = false, length = 32)
private AccountSmsCodePurpose purpose;
// + getter/setter
```

### expand-migrate-contract 跳步说明

V7 + V8 都是 expand-only 操作（加 nullable 列 / 加默认值列）；M1.3 时点尚无生产用户（per server CLAUDE.md § 五），`<expand + contract>` 合并合规。

## 反枚举设计（边界确认）

| 端点 | 反枚举对象 | 实现 |
|---|---|---|
| `POST /me/deletion-codes` | "账号是否 ACTIVE" 不外泄 | FR-003：FROZEN/ANONYMIZED 持有效 token → 401（与 token 缺失同路径） |
| `POST /me/deletion` | "code 是否正确 / 已用 / 过期 / 不属于本账号" 不外泄 | FR-007：4 类失败 → 同一 `INVALID_DELETION_CODE` 401，body 字节级一致 |
| 跨 use case | 与 register/login/refresh/logout-all 既有 401 路径同字节 | T6 `CrossUseCaseEnumerationDefenseIT` 扩展验证 |
| Timing defense | "正确 code 但已过期" vs "错码" 时延差 ≤ 50ms | dummy hash 计算（per phone-sms-auth FR-006 既有 pattern）；本 spec 复用 |

## 事件流（outbox + Spring Modulith）

```text
DeleteAccountUseCase
  │  (after status=FROZEN persisted)
  ▼
ApplicationEventPublisher.publishEvent(AccountDeletionRequestedEvent)
  │
  ▼
Spring Modulith Event Publication Registry
  │  (持久化到 event_publication 表，与事务同提交)
  ▼
[本期无 listener consume]
  │
  └── (M2+) mbw-pkm 等模块订阅 → 标记关联数据为"待清理" → anonymize-frozen-accounts scheduler 触发实际清理
```

订阅契约：`@ApplicationModuleListener void on(AccountDeletionRequestedEvent event)` — 在订阅模块完成最终一致处理后 ack；失败 → outbox 重试（Spring Modulith 默认行为）。

## 测试策略

| 层 | 测试类 | 覆盖 |
|---|---|---|
| Domain unit | `AccountTest` 扩展 | `markFrozen` happy path + 非 ACTIVE invariant 违反 → IllegalAccountStateException |
| Domain unit | `AccountStateMachineTest` 扩展 | facade `markFrozen` 调 underlying account 行为 |
| Domain unit | `AccountSmsCodePurposeTest` (新) | enum 值齐 |
| App unit | `SendDeletionCodeUseCaseTest` (新) | 限流 / SMS 失败 / status 非 ACTIVE / happy path |
| App unit | `DeleteAccountUseCaseTest` (新) | 限流 / 错码 / 过期 / 已用 / status 非 ACTIVE / SmsCode 不存在 / refresh revoke 失败 → 回滚 / happy path |
| Infra IT | `AccountSmsCodeRepositoryImplIT` 扩展 | `findActiveByPurposeAndAccountId` 三场景：active 命中 / used 排除 / expired 排除；purpose 隔离（PHONE_SMS_AUTH 不被 DELETE_ACCOUNT 查询命中） |
| Infra IT | `AccountRepositoryImplIT` 扩展 | freezeUntil persist + restore round-trip |
| Web IT | `AccountDeletionControllerE2EIT` (新) | 9 acceptance scenarios + Edge Cases 覆盖；Testcontainers PG + Redis + WireMock SMS |
| Web IT | `AccountDeletionConcurrencyIT` (新) | 同账号 5 并发提交同 code → 仅 1 成功（DB 行锁 + markUsed UNIQUE 约束保证） |
| Cross-spec IT | `CrossUseCaseEnumerationDefenseIT` 扩展 | 加 deletion 4 类失败响应字节级断言 |

## API 契约变更（OpenAPI + 前端 client）

新 endpoint：

- `POST /api/v1/accounts/me/deletion-codes` — Springdoc 自动；`@SecurityRequirement(name = "bearerAuth")` + `@Operation(summary = "Send deletion confirmation SMS code", description = "...")`
- `POST /api/v1/accounts/me/deletion` — 同上 + `@RequestBody DeleteAccountRequest`

OpenAPI snapshot：`api-docs.snapshot.json` 需在 T9 重生成（与 account-profile T9 同 pattern）。

前端 client（no-vain-years-app）`pnpm api:gen:dev` 自动生成新 generated 客户端方法 `getAccountDeletionApi().sendCode()` / `getAccountDeletionApi().delete()`，由 app 仓单独 PR 接入（不在本仓范围）。

## Constitution Check

- ✅ **Modular Monolith**：仅在 `mbw-account` 内改动 + 新事件在 `api.event`，未跨模块直接依赖
- ✅ **DDD 5-Layer**：domain (markFrozen / event) / application (use cases) / infra (jpa) / web (controller) 严格分层
- ✅ **TDD Strict**：每个 task 红绿循环；新增的 record 类（Command）TDD 例外
- ✅ **Repository pattern**：domain 接口 + JPA impl 分离（既有 pattern 不破）
- ✅ **No cross-schema FK**：本 spec 不引入新 FK
- ✅ **Flyway immutable**：V7 + V8 均新建文件
- ✅ **JDK 21 + Spring Boot 3.5.x**：无升级
- ✅ **OpenAPI 单一真相源**：Springdoc 自动生成，spec.md 不重复定义字节
- ✅ **No password / token in logs**：log 仅 accountId + freezeUntil
- ✅ **Anti-pattern 反 spec drift**：本 spec 与 [`../account-state-machine.md`](../account-state-machine.md) 完全对齐（无 redefinition）

## 反模式（明确避免）

- ❌ 在 phone-sms-auth use case 内嵌入 cancel-deletion 逻辑（破坏反枚举不变性）— 走 dedicated endpoint per CL-002
- ❌ 引入 password 二次验证字段（与 ADR-0016 冲突）— SMS only per CL-001
- ❌ 新建 `account.account_deletion_code` 独立表（重复基础设施）— 复用 + purpose enum per CL-003
- ❌ 注销后保留 access token 服务能力（GET /me 可读）— 与 account-profile FR-009 冲突 per CL-004
- ❌ 异步 transition（事件驱动状态变更）— 状态变化必须同事务原子完成 per FR-006
- ❌ 同步等 outbox consumer ack（耦合写入路径与消费速率）— Spring Modulith outbox 默认 fire-and-forget per modular-strategy
- ❌ deletion code 与 phone-sms-auth code 同 purpose 共享存储（数据串话风险）— purpose enum 物理隔离 per CL-005

## References

- [`./spec.md`](./spec.md)
- [`../account-state-machine.md`](../account-state-machine.md)
- [`../account-profile/plan.md`](../account-profile/plan.md) — 同模式 use case 的 plan 模板
- [`../../auth/refresh-token/plan.md`](../../auth/refresh-token/plan.md) — refresh token 持久化设计
- [`../../auth/logout-all/plan.md`](../../auth/logout-all/plan.md) — `revokeAllForAccount` 复用源
- [PRD § 5.5 账号注销](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#55-账号注销)
- [meta `docs/architecture/modular-strategy.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/architecture/modular-strategy.md)
- [ADR-0016](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md) / [ADR-0011](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0011-rate-limit-service.md)
