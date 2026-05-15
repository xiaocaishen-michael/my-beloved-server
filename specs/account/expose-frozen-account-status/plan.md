# Implementation Plan: Expose Frozen Account Status

**Spec**: [`./spec.md`](./spec.md) · **Tasks**: [`./tasks.md`](./tasks.md)
**Phase**: M1.X(spec C `delete-account-cancel-deletion-ui` impl 前置 server PR；surgical edit on phone-sms-auth)
**Created**: 2026-05-07

> 本 plan 是 surgical edit on `phone-sms-auth` use case：仅 4 处改动（domain exception 新增 + use case branch 拆分 + web advice handler 新增 + timing executor 加 bypassPad 参数）+ 1 处既有 IT 修订 + 1 处 spec amendment。无 DB migration、无跨模块依赖、无新 endpoint、无 OpenAPI 手写 annotation。
>
> **关键差异 vs anonymize-frozen-accounts plan**：无 scheduler、无 V migration、无 batch、无新 endpoint；改动 LOC ≤ 200（per spec SC-008）；目标 PR 体量 ≈ docs（spec.md / plan.md / tasks.md）+ 4 java files 改动 + 2 IT files 改动 + 1 spec amendment。

## 架构层级与职责（DDD 五层，仅列改动）

```text
mbw-account/
├── api/error/                                      — 不动（错误码常量定义在 domain.exception 类中,per server CLAUDE.md § 三 既有 pattern）
│
├── domain/
│   ├── exception/
│   │   └── AccountInFreezePeriodException.java     — 新建（per spec FR-001 + CL-001 + CL-005）
│   └── service/
│       └── TimingDefenseExecutor.java              — 改（加可选 bypassPad: Predicate<Throwable> 参数,per spec FR-004 + CL-003;既有 2-arg signature 通过 default predicate=always-false 保留向后兼容）
│
├── application/
│   └── usecase/
│       └── UnifiedPhoneSmsAuthUseCase.java         — 改（line 132-134 FROZEN/ANONYMIZED 共抛 InvalidCredentialsException 拆开;execute() 调 timing executor 时传 bypassPad lambda）
│
└── web/
    └── exception/
        └── AccountWebExceptionAdvice.java          — 改（新增 @ExceptionHandler(AccountInFreezePeriodException.class) handler,per spec FR-003）

mbw-account/src/test/java/com/mbw/account/
├── application/usecase/
│   └── UnifiedPhoneSmsAuthUseCaseTest.java         — 改（既有 8 case 续绿;新增 3 case: FROZEN→AccountInFreezePeriodException + FROZEN bypass timing pad + ANONYMIZED 仍走 timing pad）
└── domain/service/
    └── TimingDefenseExecutorTest.java              — 改 / 新建（既有可能没 unit test,需补;新增 bypassPad 行为 case;若既有有则改既有）

mbw-app/src/test/java/com/mbw/app/account/
├── SingleEndpointEnumerationDefenseIT.java         — 改（per spec CL-004 + SC-002:删 FROZEN case 改 3 路径;头部 javadoc 加注释指向 SC-001 IT）
└── FrozenAccountStatusDisclosureIT.java            — 新建（per spec SC-001:FROZEN 100 次请求 → 全部 403 + body code=ACCOUNT_IN_FREEZE_PERIOD + body freezeUntil + DB 不变 + 无 refresh_token 新行）

specs/auth/phone-sms-auth/
└── spec.md                                         — 改（per spec FR-008:FR-005 第 3 分支拆开 + FR-006 timing defense 范围明示 + SC-003 路径数 4→3 + 新增 Clarifications CL-006 引用 spec D）

# 不动文件列表(防 Surgical Edits 越界):
# - mbw-shared/web/GlobalExceptionHandler.java（不引入跨模块异常）
# - mbw-account/api/sms/SmsCodeService.java（SMS code 顺序不变,per CL-002）
# - mbw-account/web/controller/AccountAuthController.java（controller 仅参数转换,无业务）
# - mbw-account/application/command/PhoneSmsAuthCommand.java（无 schema 变化）
# - mbw-account/application/result/PhoneSmsAuthResult.java（无 schema 变化）
# - mbw-account/domain/exception/InvalidCredentialsException.java（ANONYMIZED + 码错路径仍用,行为不变）
# - mbw-account/domain/model/Account.java + AccountStateMachine.java（不动 canLogin 等既有 domain service）
# - mbw-app/src/main/resources/db/migration/account/*.sql（零 migration）
# - 任何 cancel-deletion / delete-account / anonymize-frozen-accounts 既有 use case
```

## 核心 use case 流程

### Endpoint 不变：`POST /api/v1/accounts/phone-sms-auth`

**目的**：phone-sms-auth use case 4 条主路径中 FROZEN 单独拆出 disclosure 路径；其他 3 路径行为完全不变。

**触发 / 响应（变更对照）**：

| 输入条件 | spec D 前 | spec D 后 |
|---|---|---|
| ACTIVE + 合法码 | HTTP 200 + `{accountId, accessToken, refreshToken}` | ✅ 不变 |
| 未注册 + 合法码 | HTTP 200 + 新 accountId + outbox `AccountCreatedEvent` | ✅ 不变 |
| **FROZEN + 合法码** | **HTTP 401 + body code=INVALID_CREDENTIALS（反枚举吞）** | **HTTP 403 + body code=ACCOUNT_IN_FREEZE_PERIOD + body freezeUntil（disclosure）** |
| ANONYMIZED + 合法码（边界） | HTTP 401 + body code=INVALID_CREDENTIALS | ✅ 不变 |
| 任意 status + 错误码 | HTTP 401 + body code=INVALID_CREDENTIALS | ✅ 不变 |
| 任意 status + 限流命中 | HTTP 429 + Retry-After | ✅ 不变 |

**`UnifiedPhoneSmsAuthUseCase.doExecute` 改动定位**（line 130-135）：

```java
// 当前实现
Optional<Account> existing = accountRepository.findByPhone(phone);
if (existing.isPresent()) {
    Account account = existing.get();
    if (!AccountStateMachine.canLogin(account)) {
        // FROZEN / ANONYMIZED — anti-enumeration: same byte shape as 码错
        throw new InvalidCredentialsException();
    }
    return transactionTemplate.execute(status -> persistLogin(account));
}

// spec D 改动后
Optional<Account> existing = accountRepository.findByPhone(phone);
if (existing.isPresent()) {
    Account account = existing.get();
    if (account.status() == AccountStatus.FROZEN) {
        // Disclosure path (per spec D expose-frozen-account-status FR-002):
        // explicit 403 to support spec C login flow cancel-deletion modal.
        // ANONYMIZED remains anti-enumeration-collapsed below.
        throw new AccountInFreezePeriodException(account.freezeUntil());
    }
    if (!AccountStateMachine.canLogin(account)) {
        // ANONYMIZED — anti-enumeration: same byte shape as 码错 (preserved per spec D Out of Scope)
        throw new InvalidCredentialsException();
    }
    return transactionTemplate.execute(status -> persistLogin(account));
}
```

**`UnifiedPhoneSmsAuthUseCase.execute` 改动定位**（line 115-117）：

```java
// 当前实现
public PhoneSmsAuthResult execute(PhoneSmsAuthCommand cmd) {
    return TimingDefenseExecutor.executeInConstantTime(TIMING_TARGET, () -> doExecute(cmd));
}

// spec D 改动后
public PhoneSmsAuthResult execute(PhoneSmsAuthCommand cmd) {
    return TimingDefenseExecutor.executeInConstantTime(
            TIMING_TARGET,
            () -> doExecute(cmd),
            ex -> ex instanceof AccountInFreezePeriodException);
}
```

**`TimingDefenseExecutor.executeInConstantTime` 改动**：

```java
// 既有 2-arg signature 保留（向后兼容；其他 callsites 无需改动）
public static <T> T executeInConstantTime(Duration target, Supplier<T> body) {
    return executeInConstantTime(target, body, ex -> false);
}

// 新增 3-arg signature（spec D 主入口）
public static <T> T executeInConstantTime(
        Duration target, Supplier<T> body, Predicate<Throwable> bypassPad) {
    long startNanos = System.nanoTime();
    try {
        return body.get();
    } catch (Throwable t) {
        if (bypassPad.test(t)) {
            // FROZEN disclosure path skips wall-clock pad
            // (per spec D FR-004 + CL-003)
            throw t;
        }
        throw t;
    } finally {
        // padRemaining only runs when bypassPad didn't catch
        // (catch-rethrow above bypasses the finally pad via a return/throw flow guard)
    }
}
```

> **实现细节注意**：上述 try/catch/finally 的 control flow 让 `bypassPad.test(t)==true` 路径绕过 finally pad — Java spec 上 finally 总是执行,所以需要用 boolean flag 切换 padRemaining 是否调用,而不是直接 catch/rethrow。具体实现:
>
> ```java
> public static <T> T executeInConstantTime(
>         Duration target, Supplier<T> body, Predicate<Throwable> bypassPad) {
>     long startNanos = System.nanoTime();
>     boolean shouldPad = true;
>     try {
>         return body.get();
>     } catch (Throwable t) {
>         if (bypassPad.test(t)) {
>             shouldPad = false;
>         }
>         throw t;
>     } finally {
>         if (shouldPad) {
>             padRemaining(target, startNanos);
>         }
>     }
> }
> ```

### Web Advice：`AccountWebExceptionAdvice.onAccountInFreezePeriod`

**新增 handler（per spec FR-003 + CL-001）**：

```java
@ExceptionHandler(AccountInFreezePeriodException.class)
public ProblemDetail onAccountInFreezePeriod(AccountInFreezePeriodException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN, "Account in freeze period; cancel deletion to re-activate");
    problem.setTitle("Account in freeze period");
    problem.setProperty("code", AccountInFreezePeriodException.CODE);
    problem.setProperty("freezeUntil", ex.getFreezeUntil().toString());
    return problem;
}
```

**`@Order` 不变**（HIGHEST_PRECEDENCE + 100，与既有 `AccountWebExceptionAdvice` 同优先级）。

### Domain Exception：`AccountInFreezePeriodException`

**新建（per spec FR-001 + CL-005）**：

```java
package com.mbw.account.domain.exception;

import java.time.Instant;

/**
 * Domain exception for FROZEN account login attempts on phone-sms-auth.
 *
 * <p>Thrown when a phone-sms-auth request authenticates with a valid
 * SMS code but the matched account is in the 15-day delete-account
 * grace window (status == FROZEN). The web advice maps this to
 * HTTP 403 with code {@code ACCOUNT_IN_FREEZE_PERIOD} + extended
 * field {@code freezeUntil} (ISO 8601 UTC) so the client (per
 * spec C delete-account-cancel-deletion-ui) can trigger a "cancel
 * deletion?" intercept modal.
 *
 * <p><b>Disclosure boundary</b>: this exception is intentionally
 * NOT routed through anti-enumeration uniform 401 (per spec D
 * expose-frozen-account-status FR-001~FR-004). FROZEN status is
 * explicitly disclosed to support spec C login flow cancel-deletion
 * modal; ANONYMIZED status remains anti-enumeration-collapsed via
 * {@link InvalidCredentialsException}. Do not collapse this back
 * to InvalidCredentialsException without revisiting spec D.
 *
 * <p>Wall-clock note: callers should signal
 * {@link com.mbw.account.domain.service.TimingDefenseExecutor#executeInConstantTime}
 * to bypass the 400ms pad for this exception type (per spec D
 * FR-004 + CL-003) — the disclosure already exists, padding wastes
 * worker time without security gain.
 */
public class AccountInFreezePeriodException extends RuntimeException {

    public static final String CODE = "ACCOUNT_IN_FREEZE_PERIOD";

    private final Instant freezeUntil;

    public AccountInFreezePeriodException(Instant freezeUntil) {
        super(CODE);
        this.freezeUntil = freezeUntil;
    }

    public Instant getFreezeUntil() {
        return freezeUntil;
    }
}
```

## 数据流（FROZEN disclosure 路径生命周期）

```text
client POST /api/v1/accounts/phone-sms-auth {phone, code}
  │
  ▼
AccountAuthController.phoneSmsAuth(request) → useCase.execute(cmd)
  │
  ▼
UnifiedPhoneSmsAuthUseCase.execute(cmd)
  │
  ▼
TimingDefenseExecutor.executeInConstantTime(400ms, () -> doExecute(cmd), bypassPad: AccountInFreezePeriodException)
  │
  ▼
doExecute(cmd):
  1. PhonePolicy.validate(phone)
  2. rateLimitService.consumeOrThrow("auth:" + phone, authBandwidth)
  3. smsCodeService.verify(phone, code) → success → Redis DEL
  4. accountRepository.findByPhone(phone) → Optional.of(frozen account)
  5. account.status() == FROZEN ?
     ├─ YES: throw new AccountInFreezePeriodException(account.freezeUntil())
     └─ NO + ANONYMIZED: throw new InvalidCredentialsException()  [unchanged]
     └─ NO + ACTIVE: persistLogin(account)  [unchanged]
  │
  ▼
TimingDefenseExecutor catch AccountInFreezePeriodException:
  - bypassPad.test(ex) == true → shouldPad = false
  - rethrow exception (no padRemaining)
  - wall-clock < 100ms (vs 400ms for non-bypass paths)
  │
  ▼
@RestControllerAdvice 路径:
  AccountWebExceptionAdvice.onAccountInFreezePeriod(ex)
  → ProblemDetail.forStatus(403)
  → setTitle("Account in freeze period")
  → setProperty("code", "ACCOUNT_IN_FREEZE_PERIOD")
  → setProperty("freezeUntil", "2026-05-21T03:00:00Z")
  │
  ▼
HTTP 403 application/problem+json:
  {
    "type": "about:blank",
    "title": "Account in freeze period",
    "status": 403,
    "detail": "Account in freeze period; cancel deletion to re-activate",
    "code": "ACCOUNT_IN_FREEZE_PERIOD",
    "freezeUntil": "2026-05-21T03:00:00Z",
    "instance": "/api/v1/accounts/phone-sms-auth"
  }
```

## 关键技术决策（per spec.md Clarifications）

| ID | 决策 | 实施细节（plan 层落地）|
|---|---|---|
| CL-001 | ProblemDetail body 暴露 `freezeUntil` ISO 8601 UTC | `AccountInFreezePeriodException` 构造参 `Instant`；getter `getFreezeUntil()`；advice handler `setProperty("freezeUntil", ex.getFreezeUntil().toString())`；ISO 8601 由 `Instant.toString()` 默认产出（`2026-05-21T03:00:00.123456Z` 形态，UTC + 微秒精度，与 PG TIMESTAMPTZ 精度对齐 per meta MEMORY `feedback_pg_timestamptz_truncate_micros.md` — IT 断言时 `.truncatedTo(MICROS)`）|
| CL-002 | SMS code 消费顺序不变 | `UnifiedPhoneSmsAuthUseCase.doExecute` 不动 line 124-127 SMS verify + Redis DEL；FROZEN 检查仍在 line 130 之后；用户拿 403 后走 cancel-deletion endpoint（独立 `cancel:<phone>` bucket）|
| CL-003 | TimingDefenseExecutor 加可选 bypassPad 参数 | 既有 2-arg signature 通过 `executeInConstantTime(target, body) { return executeInConstantTime(target, body, ex -> false); }` 保留向后兼容；新 3-arg signature 用 `boolean shouldPad` flag 在 catch 中条件 set false；finally 内 if 决定是否调 padRemaining；既有 callsites（grep 仅 1 处 = `UnifiedPhoneSmsAuthUseCase.execute`）改造为 3-arg 调用 |
| CL-004 | 修订既有 `SingleEndpointEnumerationDefenseIT` 改 3 路径 | 删 IT 中 FROZEN test fixture + 相关 1000 次循环里 FROZEN 子集；保留 ACTIVE 成功 / 未注册自动创建 / ANONYMIZED + 码错 共反枚举吞 3 路径；class-level javadoc 加 `<p>FROZEN disclosure path covered separately by FrozenAccountStatusDisclosureIT (spec D SC-001).` |
| CL-005 | AccountInFreezePeriodException javadoc 标 disclosure intent | 类级 javadoc 含 `<p><b>Disclosure boundary</b>:` 段（具体内容 per spec FR-001 完整 javadoc 已在本 plan domain exception 段示例中给出）|

## 跨模块影响（零）

- ✅ 不依赖 `mbw-shared` 新增任何东西（错误码归属 `mbw-account.domain.exception` 内部常量，per server CLAUDE.md § 三 错误码归属表第 1 档）
- ✅ 不动其他 mbw-account use case（cancel-deletion / delete-account / anonymize-frozen-accounts 行为完全不变；spec D 是 phone-sms-auth surgical edit）
- ✅ 不动数据库 schema / Flyway migration（FR-010）
- ✅ 不动 controller 层 / DTO（FR-009 OpenAPI 自动反射）
- ✅ ArchUnit + Spring Modulith Verifier 0 violation（domain.exception 包既有，无新跨模块包引用）

## 测试策略

### 单元测试（domain + application 层；TDD 红绿）

| 文件 | 改动 | 覆盖场景 |
|---|---|---|
| `UnifiedPhoneSmsAuthUseCaseTest.java` | 改（既有 8 case 续绿 + 新增 3 case）| ① FROZEN account → throws `AccountInFreezePeriodException` with `freezeUntil` matched ② FROZEN path bypasses timing defense（mock fast path → wall-clock < 100ms）③ ANONYMIZED account 仍 throws `InvalidCredentialsException` + wall-clock ≥ 380ms（保留 timing pad）|
| `TimingDefenseExecutorTest.java` | 新建 / 改（既有可能没单测）| ① 2-arg signature 不带 bypassPad → 异常仍 pad 到 target ② 3-arg signature bypassPad.test==true → 异常 rethrow + wall-clock < target ③ 3-arg signature bypassPad.test==false → 异常 pad 到 target ④ happy path 不受 bypassPad 影响 |
| `AccountInFreezePeriodExceptionTest.java` | 新建 | 构造 + getter + CODE 常量值断言；纯 record-like 行为 |

### 集成测试（mbw-app 层；Testcontainers PG + Redis）

| 文件 | 改动 | 覆盖场景 |
|---|---|---|
| `FrozenAccountStatusDisclosureIT.java` | 新建（per spec SC-001）| 预设 FROZEN 账号 + freeze_until=now+14d；100 次发 sms-codes + phone-sms-auth → 全部 status=403 + body code=ACCOUNT_IN_FREEZE_PERIOD + body freezeUntil 字段存在 + DB account 表 last_login_at/status/freeze_until 不变 + DB refresh_token 无新行 |
| `SingleEndpointEnumerationDefenseIT.java` | 改（per spec SC-002 + CL-004）| 删 FROZEN case；保 3 路径 1000 次混合请求字节级一致 + P95 时延差 ≤ 50ms；class javadoc 加 FROZEN disclosure 注释指向新 IT |

### Web layer 测试

`@WebMvcTest` 覆盖：可选 — `AccountWebExceptionAdvice` 的 `onAccountInFreezePeriod` handler 在 IT 中已端到端验证；额外加 mock controller exception inject 单测**不强制**（per server CLAUDE.md § 一 TDD 例外 "纯转发 controller 由 @WebMvcTest 自然覆盖"，本 spec advice 由 IT 直接覆盖更高保真）。

### Spec 同步测试

phone-sms-auth/spec.md amendment 不需 IT 验证；由 PR review + analyze gate（task #5）确保 spec D 文档与 phone-sms-auth amendment 一致。

## 实现 stage（与 tasks.md 对齐）

| Stage | 内容 | 依赖 |
|---|---|---|
| **S0** | 同 PR amend phone-sms-auth/spec.md（FR-005 / FR-006 / SC-003 + CL-006）| 无 |
| **S1** | `AccountInFreezePeriodException` 新建 + 单测（TDD 红绿）| 无 |
| **S2** | `TimingDefenseExecutor` 加 3-arg signature + 单测（TDD 红绿）| S1（不严格，可并行；但概念上 bypassPad 是为 FROZEN exception 服务，先建后引）|
| **S3** | `UnifiedPhoneSmsAuthUseCase.doExecute` 拆 FROZEN/ANONYMIZED + `execute` 加 bypassPad lambda + `UnifiedPhoneSmsAuthUseCaseTest` 改 + 新增 3 case（TDD 红绿）| S1 + S2 |
| **S4** | `AccountWebExceptionAdvice` 加 handler（TDD 通过 IT 覆盖，advice 单测可选）| S1 |
| **S5** | `SingleEndpointEnumerationDefenseIT` 修订 改 3 路径（per CL-004）| S3 + S4（既有 IT 改动后必须仍绿）|
| **S6** | `FrozenAccountStatusDisclosureIT` 新建（per SC-001）| S3 + S4 |
| **S7** | OpenAPI Springdoc 自动同步验证：`./mvnw spring-boot:run -pl mbw-app` + `curl localhost:8080/v3/api-docs \| jq` 含 ACCOUNT_IN_FREEZE_PERIOD（per SC-004）| S4 |
| **S8** | 真后端冒烟（admin SQL 改账号 status=FROZEN + curl 跑流程 + 截图）（per SC-007）| S5 + S6 + S7 |
| **S9** | `./mvnw verify` 全绿 + `git commit` + `gh pr create` + auto-merge | S5 + S6 + S7 + S8 |

## 衔接边界

- **上游依赖**：本 spec 不依赖任何 spec ship；可独立合并
- **下游消费方**：no-vain-years-app `spec/delete-account-cancel-deletion-ui/` impl session（spec C `/speckit.implement`）— spec D PR ship 后才开
- **并行 PR**：本 PR 与 spec C impl PR **强串联**（spec C impl T0 阶段必须先跑 `pnpm api:gen` 拉到含 ACCOUNT_IN_FREEZE_PERIOD 的 client）；不可并发
- **OpenAPI breaking change**：本 spec 改 phone-sms-auth 响应 — 旧 401 INVALID_CREDENTIALS（FROZEN 路径）→ 新 403 ACCOUNT_IN_FREEZE_PERIOD；M1 阶段无真实用户（per phone-sms-auth CL-002 + meta CLAUDE.md 业务命名 § "M1 v0.x.x 无真实用户"），不需 backward compat shim
- **Spec drift 防御**：FR-008 + SC-005 强制同 PR 修订 phone-sms-auth/spec.md；防 phone-sms-auth FR-005/FR-006/SC-003 与新行为不一致（per constitution Anti-Patterns "Spec drift > 1 week"）

## Out of Scope（plan 层 explicit 不做）

- 既有 `register-by-phone` / `login-by-phone-sms` / `login-by-password` use case 改动（已删除，per phone-sms-auth T0 spec D 后置依赖）
- `RefreshTokenUseCase` / `LogoutAllSessionsUseCase` / `GetAccountProfileUseCase` 等其他 use case 反枚举行为评估（不在 spec D scope；若 PRD 后续要求其他 endpoint disclosure 单起 spec）
- ANONYMIZED disclosure（per spec Out of Scope）
- IDENTITY_IN_FREEZE_PERIOD（per spec Out of Scope）
- mbw-billing 配额 / 权益相关检查（M3+）
- Performance regression（M1.3 引入 CI 性能基线，不预跑）
- 跨模块事件发布 / 订阅（spec D 无跨模块事件）
- Admin endpoint 给 SUPPORT 团队解锁 frozen 账号（M3+ admin 模块）
