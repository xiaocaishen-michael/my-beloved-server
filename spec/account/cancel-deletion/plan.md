# Implementation Plan: Cancel Deletion

**Spec**: [`./spec.md`](./spec.md) · **Tasks**: [`./tasks.md`](./tasks.md)
**Phase**: M1.3（账号生命周期闭环 — FROZEN → ACTIVE 撤销）
**Created**: 2026-05-06

> 本 plan 复用 [`../delete-account/plan.md`](../delete-account/plan.md) 的大部分基础设施（V7 freeze_until + V8 purpose enum + AccountSmsCodeRepository.findActiveByPurposeAndAccountId + AccountStateMachine facade）。本文件聚焦 cancel-deletion 独有的 application + web 层 + state transition 反向行为。

## 架构层级与职责（DDD 五层，仅列改动）

```text
mbw-account/
├── api/event/
│   └── AccountDeletionCancelledEvent.java            — 新建（FR-008）
│
├── domain/
│   ├── model/
│   │   └── Account.java                              — 改（加 markActiveFromFrozen 行为）
│   └── service/
│       └── AccountStateMachine.java                  — 改（加 markActiveFromFrozen facade）
│
├── application/
│   ├── command/
│   │   ├── SendCancelDeletionCodeCommand.java        — 新建
│   │   └── CancelDeletionCommand.java                — 新建
│   ├── result/
│   │   └── CancelDeletionResult.java                 — 新建（复用 LoginResult schema）
│   └── usecase/
│       ├── SendCancelDeletionCodeUseCase.java        — 新建
│       └── CancelDeletionUseCase.java                — 新建
│
└── web/
    ├── controller/
    │   └── CancelDeletionController.java             — 新建
    └── request/
        ├── SendCancelDeletionCodeRequest.java        — 新建
        └── CancelDeletionRequest.java                — 新建
```

**不动**：`AccountSmsCodeJpaEntity` / `AccountJpaEntity` / `AccountSmsCodeRepository` 已在 delete-account 落地的扩展。

## 核心 use case 流程

### Endpoint 1: `POST /api/v1/auth/cancel-deletion/sms-codes`

**目的**：撤销注销第一步——为可能 FROZEN 的账号发 SMS code（反枚举：未注册 / ACTIVE / ANONYMIZED 全部返 200 不发短信）。

**入参**：HTTP body `{phone: string}`；无鉴权。

**响应**：200 No Content（统一）/ 429 ProblemDetail（限流）/ 400 ProblemDetail（schema）/ 503 ProblemDetail（SMS 服务挂 + FROZEN 命中，per spec CL-001 接受小信息泄露）。

**流程**：

1. Web — `CancelDeletionController.sendCode(@Valid body)`：取 phone + clientIp → 调 UseCase → 返 `ResponseEntity.ok().build()`
2. Application — `SendCancelDeletionCodeUseCase`：
   1. `RateLimitService.tryAcquire("cancel-code:phone:<hash(phone)>", 60s, 1)` — phone hash 维度防泄露
   2. `RateLimitService.tryAcquire("cancel-code:ip:<ip>", 60s, 5)`
   3. `AccountRepository.findByPhone(phone)` → 三种结果走分支：
      - 未找到 / ACTIVE / ANONYMIZED → **dummy hash 计算（per phone-sms-auth FR-006 timing defense）+ 不写 sms_code + 不调 SmsClient** → 返成功（反枚举吞）
      - FROZEN + freeze_until > now → 真发码：生成 code + hash + persist (purpose=CANCEL_DELETION) + SmsClient.send
      - FROZEN + freeze_until <= now（已过期 grace） → 同 "未找到" 路径（视为不可撤销）
   4. log INFO `cancel-deletion-code attempted phoneHash=<hash>`（不打 phone 明文，phone hash 做审计）
   - SMS 失败抛 `SmsSendFailedException`（503）— per spec CL-001 接受信息泄露

### Endpoint 2: `POST /api/v1/auth/cancel-deletion`

**目的**：撤销注销第二步——FROZEN → ACTIVE transition + 发新 token。

**入参**：HTTP body `{phone: string, code: string}`；无鉴权。

**响应**：200 + `LoginResponse {accountId, accessToken, refreshToken}`（成功）/ 401 ProblemDetail INVALID_CREDENTIALS（所有失败统一） / 429 / 400。

**流程**：

1. Web — `CancelDeletionController.cancel(@Valid body)`：调 UseCase → 返 `ResponseEntity.ok(LoginResponse)`
2. Application — `CancelDeletionUseCase` (`@Transactional(rollbackFor = Throwable.class)`)：
   1. `RateLimitService.tryAcquire("cancel-submit:phone:<hash>", 60s, 5)` + `cancel-submit:ip:<ip>` 60s 10
   2. `AccountRepository.findByPhone(phone)` → 走分支：
      - 未找到 / ACTIVE / ANONYMIZED / FROZEN+freeze_until 已过期 → dummy hash 比对（timing defense） → 抛 `InvalidCredentialsException`
      - FROZEN + freeze_until > now → 进入 cancel 流程
   3. `AccountSmsCodeRepository.findActiveByPurposeAndAccountId(CANCEL_DELETION, account.id, now)` → 不存在 / 已用 / 过期 抛 `InvalidCredentialsException`
   4. `Hasher.hash(code).equals(record.codeHash)` → 失败抛 `InvalidCredentialsException`
   5. `accountSmsCodeRepository.markUsed(record.id, now)`
   6. `AccountStateMachine.markActiveFromFrozen(account, now)` — 校验 status==FROZEN && freeze_until > now → 转 ACTIVE + 清 freeze_until
   7. `AccountRepository.save(account)` — JPA persist
   8. `eventPublisher.publish(new AccountDeletionCancelledEvent(accountId, cancelledAt=now, occurredAt=now))`
   9. issue token：`var pair = TokenIssuer.issueAccessAndRefresh(account.id, now)`
   10. persist refresh token：`refreshTokenRepository.save(RefreshTokenRecord.create(hash(pair.refreshRaw), accountId, expiresAt))`
   11. log INFO `account.deletion.cancelled accountId=<id> daysRemainingAtCancel=<int>`（埋点 placeholder per FR-011）
   - 任一步失败 → 全部回滚

## 数据流（请求生命周期）

```text
Client (web/native, FROZEN 账号已 logged out)
  │
  │  POST /auth/cancel-deletion/sms-codes  Body: {"phone": "+8613800138000"}
  ▼
（无 JwtAuthFilter — 公开端点）
  │
  ▼
CancelDeletionController.sendCode(@Valid req, HttpServletRequest)
  ▼
SendCancelDeletionCodeUseCase.execute(cmd)
  │  - RateLimit phone hash + ip
  │  - findByPhone → 4 类分支
  │  - FROZEN+grace → SmsClient.send (其他 dummy)
  ▼
200 OK (统一,反枚举)

------ FROZEN 用户收到 SMS code ------

Client
  │  POST /auth/cancel-deletion  Body: {"phone": "...", "code": "123456"}
  ▼
CancelDeletionController.cancel(@Valid req, HttpServletRequest)
  ▼
CancelDeletionUseCase.execute(cmd) [TRANSACTIONAL]
  │  - RateLimit (phone hash + ip + code)
  │  - findByPhone (FROZEN+grace 才进 cancel,其他 dummy + 401)
  │  - findActiveByPurpose(CANCEL_DELETION, accountId)
  │  - hash(code) 比对
  │  - markUsed(code)
  │  - markActiveFromFrozen(account)
  │  - save(account)
  │  - publish(AccountDeletionCancelledEvent)
  │  - issue token pair
  │  - persist refresh_token (per refresh-token FR-009)
  ▼
200 OK + LoginResponse {accountId, accessToken, refreshToken}
```

## 复用既有基础设施

| 资产 | 来源 | 用途 |
|---|---|---|
| `AccountSmsCodeRepository.findActiveByPurposeAndAccountId` | delete-account T4 (本周期 ship) | purpose 隔离查 active code |
| `AccountSmsCodeRepository.markUsed` | phone-sms-auth | 标 code 已用 |
| V7 freeze_until / V8 purpose enum migration | delete-account T0+T1 | 复用同 schema |
| `AccountSmsCodePurpose.CANCEL_DELETION` | 本 spec 新增 enum 值 | （与 delete-account DELETE_ACCOUNT 同期扩展）|
| `RateLimitService` | 既有 | 限流 4 个键 |
| `SmsClient` | 既有 | 短信发送 |
| `TokenIssuer.issueAccessAndRefresh` | phone-sms-auth | token 签发 |
| `RefreshTokenRepository.save` | refresh-token | 持久化新 refresh token |
| `Account.markActive` (既有) + `markActiveFromFrozen` (新增) | domain | 状态 transition |
| `AccountStateMachine` | account-profile / 既有 | facade |
| `LoginResponse` schema | phone-sms-auth | 复用响应 schema |

## 新建组件

### Domain layer

- `Account.markActiveFromFrozen(Instant now)` package-private：校验 `status == FROZEN && freezeUntil != null && freezeUntil.isAfter(now)` → 不满足抛 `IllegalAccountStateException`；满足则 `this.status = ACTIVE; this.freezeUntil = null; this.updatedAt = now;`
- `AccountStateMachine.markActiveFromFrozen(Account, Instant now)` facade
- 新事件 `mbw-account/api/event/AccountDeletionCancelledEvent.java` record `(AccountId accountId, Instant cancelledAt, Instant occurredAt)`
- `AccountSmsCodePurpose` enum 加 `CANCEL_DELETION` 值（与 delete-account 同 enum 扩展，本 spec 引入此值）

### Application layer

- `SendCancelDeletionCodeCommand(String phone, String clientIp)` record
- `CancelDeletionCommand(String phone, String code, String clientIp)` record
- `CancelDeletionResult(long accountId, String accessToken, String refreshToken)` — 复用 `LoginResult` schema（or 直接复用既有 `PhoneSmsAuthResult` record）
- `SendCancelDeletionCodeUseCase`
- `CancelDeletionUseCase` `@Transactional`

### Web layer

- `SendCancelDeletionCodeRequest(@NotBlank @Pattern("^\\+\\d{8,15}$") String phone)` record
- `CancelDeletionRequest(@NotBlank @Pattern phone, @NotBlank @Pattern("\\d{6}") String code)` record
- `CancelDeletionController`：

  ```java
  @RestController
  @RequestMapping("/api/v1/auth/cancel-deletion")
  public class CancelDeletionController {

      @PostMapping("/sms-codes")
      public ResponseEntity<Void> sendCode(
              @Valid @RequestBody SendCancelDeletionCodeRequest body, HttpServletRequest req) {
          var clientIp = ClientIpExtractor.from(req);
          sendCancelDeletionCodeUseCase.execute(
              new SendCancelDeletionCodeCommand(body.phone(), clientIp));
          return ResponseEntity.ok().build();
      }

      @PostMapping
      public ResponseEntity<LoginResponse> cancel(
              @Valid @RequestBody CancelDeletionRequest body, HttpServletRequest req) {
          var clientIp = ClientIpExtractor.from(req);
          var result = cancelDeletionUseCase.execute(
              new CancelDeletionCommand(body.phone(), body.code(), clientIp));
          return ResponseEntity.ok(LoginResponse.from(result));
      }
  }
  ```

## 数据模型变更

**无新 migration**。本 spec 复用 delete-account 落地的 V7 (freeze_until) + V8 (purpose enum)。

`AccountSmsCodePurpose.CANCEL_DELETION` enum 值为 in-code 扩展，DB 字段 `VARCHAR` 自动支持。

## 反枚举设计（边界确认）

| 端点 | 反枚举对象 | 实现 |
|---|---|---|
| `POST /auth/cancel-deletion/sms-codes` | "phone 是否 FROZEN" | 4 类 phone 全返 200；FROZEN 真发短信，其他 dummy hash + 不发；时延差 ≤ 100ms（per spec SC-004） |
| `POST /auth/cancel-deletion` | "phone 是否 FROZEN / code 是否正确 / freeze_until 是否过期" | 5 类失败统一 401 INVALID_CREDENTIALS；body 字节级一致；时延差 ≤ 50ms |
| 跨 use case | 与 phone-sms-auth INVALID_CREDENTIALS 字节级一致 | T7 `CrossUseCaseEnumerationDefenseIT` 扩展验证 |

**已知小信息泄露**（per spec CL-001）：SMS 服务挂时 FROZEN 命中返 503，其他返 200 — 攻击者可推测 phone 是否 FROZEN。文档化 + 限流缓解，不进一步对齐。

## 事件流

```text
CancelDeletionUseCase
  │  (after status=ACTIVE persisted)
  ▼
ApplicationEventPublisher.publishEvent(AccountDeletionCancelledEvent)
  │  (Spring Modulith outbox 持久化,与事务同提交)
  ▼
[本期无 listener consume]
  │
  └── (M2+) mbw-pkm 订阅 AccountDeletionRequestedEvent 的模块同步订阅本事件
      → 抹去 "待清理" 标记,取消 anonymize 准备
```

## 测试策略

| 层 | 测试类 | 覆盖 |
|---|---|---|
| Domain unit | `AccountTest` 扩展 | `markActiveFromFrozen` happy + 6 类违反 invariant case |
| Domain unit | `AccountStateMachineTest` 扩展 | facade 调用断言 |
| Domain unit | `AccountSmsCodePurposeTest` 扩展 | 含 CANCEL_DELETION 值 |
| App unit | `SendCancelDeletionCodeUseCaseTest` (新) | 限流 / 4 类 phone 分支 / SMS 失败 |
| App unit | `CancelDeletionUseCaseTest` (新) | 限流 / 4 类 phone 分支 / 错码 / 过期 / 已用 / freeze_until 抢跑过期 / token issue 失败回滚 / refresh_token persist 失败回滚 / happy path |
| Web IT | `CancelDeletionControllerE2EIT` (新) | 14 acceptance scenarios + 反枚举 4 类 phone × 4 状态 + token 签发验证 |
| Web IT | `CancelDeletionConcurrencyIT` (新) | 同 phone+code 5 并发 → 仅 1 成功 transition + 4 个 401 |
| Cross-spec IT | `CrossUseCaseEnumerationDefenseIT` 扩展 | cancel-deletion 5 类失败 vs delete-account vs phone-sms-auth 字节级断言 |

## API 契约变更（OpenAPI + 前端 client）

新 endpoint：

- `POST /api/v1/auth/cancel-deletion/sms-codes` — 公开端点（无 securityRequirement）
- `POST /api/v1/auth/cancel-deletion` — 公开端点 + 响应 LoginResponse

OpenAPI snapshot 重生成（与 delete-account T13 同 pattern）。前端 client `pnpm api:gen:dev` 自动新方法 `getCancelDeletionApi().sendCode()` / `getCancelDeletionApi().cancel()`，由 app 仓单独 PR 接入。

## Constitution Check

- ✅ **Modular Monolith** / **DDD 5-Layer** / **TDD Strict** / **Repository pattern** / **Flyway immutable** / **JDK 21 + Spring Boot 3.5.x** / **OpenAPI 单一真相源** / **No password / token in logs** — 全部继承 delete-account plan 同款保证
- ✅ **No state regression**：FROZEN → ACTIVE transition 不可绕过 freeze_until > now 校验（防 scheduler 抢跑）
- ✅ **反枚举 invariant**：4 类 phone 响应字节级一致 + 时延差容忍 ≤ 100ms
- ✅ **Cross-use-case state machine consistency**：本 spec markActiveFromFrozen 与 delete-account markFrozen 反向；invariant 由 SC-007 测试覆盖

## 反模式（明确避免）

- ❌ 复用 phone-sms-auth `/sms-codes` + `/phone-sms-auth` 端点做 cancel — 破坏 phone-sms-auth FR-006 反枚举不变性 per delete-account CL-002
- ❌ FROZEN → ACTIVE 走任何非 transactional 路径（status 变更与 token issue / refresh_token persist 不同事务）— 任一失败必须回滚 status
- ❌ scheduler 与 cancel 之间 race 不防御（freeze_until > now 校验缺失） — SC-008 强制
- ❌ ANONYMIZED → ACTIVE 路径暴露（即使是 invariant 违反也应吞为 401 而非 500）— per state-machine.md "ANONYMIZED 是终态不可逆"
- ❌ cancel 后不发新 token（让用户再走一遍登录）— 与 PRD § 5.5 line 392 "撤销 → 状态转 ACTIVE → 发 token" 不符

## References

- [`./spec.md`](./spec.md)
- [`../delete-account/plan.md`](../delete-account/plan.md) — 复用大量基础设施
- [`../account-state-machine.md`](../account-state-machine.md) — 状态机 + invariants
- [`../phone-sms-auth/spec.md`](../phone-sms-auth/spec.md) — 反枚举 pattern + LoginResponse + token issuance
- [`../../auth/refresh-token/spec.md`](../../auth/refresh-token/spec.md) — refresh_token 持久化
- [PRD § 5.5 账号注销](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#55-账号注销)
- [meta `docs/architecture/modular-strategy.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/architecture/modular-strategy.md)
