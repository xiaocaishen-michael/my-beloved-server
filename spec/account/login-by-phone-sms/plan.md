# Implementation Plan: Login by Phone SMS

**Use case**: `login-by-phone-sms`
**Module**: `mbw-account`
**Spec**: [`./spec.md`](./spec.md)
**Builds on**: [`../register-by-phone/plan.md`](../register-by-phone/plan.md)（共享基础设施 + 防时延 + 限流 + SMS gateway）

> 设计原则：**最大化复用 register-by-phone 已落的基础设施**（domain services / RateLimitService / SmsCodeService / TimingDefenseExecutor / dummy bcrypt / GlobalExceptionHandler）。本 use case 仅新增 1 个 UseCase + 1 个 endpoint + 1 个 Flyway migration（加 `last_login_at` 列）。

## Constitution Check

逐条对标 [`.specify/memory/constitution.md`](../../../.specify/memory/constitution.md)：

| 原则 | 本 use case 落实 |
|---|---|
| Modular Monolith | 工作仅在 `mbw-account`；不引入跨模块依赖 |
| DDD 5 层 | 复用 domain `Account` / `AccountRepository` / `TokenIssuer`；application 新增 `LoginByPhoneSmsUseCase`；web 扩展 `AuthController`（新建）|
| TDD 强制 | 红绿循环全覆盖；UseCase 单测 + Testcontainers IT + Timing Defense IT |
| Conventional Commits | `spec(account): ...` (本 PR) + `feat(account): ...`（impl PR）|
| ArchUnit 边界守护 | 新代码不破坏既有边界 |
| DB schema 隔离 | account schema 内加列，无跨 schema 引用 |
| Expand-migrate-contract | **不适用**（纯增列，per spec.md CL-004） |

## Project Structure

```text
mbw-account/
├── src/main/java/com/mbw/account/
│   ├── api/
│   │   ├── error/                                ← 复用 InvalidCredentialsException 等（register 已建）
│   │   └── service/                              ← 无新对外接口
│   ├── domain/
│   │   ├── model/Account.java                    ← 改：加 lastLoginAt 字段（getter/with-method）
│   │   ├── repository/AccountRepository.java     ← 改：加 updateLastLoginAt(AccountId, Instant) 方法
│   │   ├── service/TokenIssuer.java              ← 复用（register 已建）
│   │   └── service/AccountStateMachine.java      ← 复用（register 已建，扩展 ACTIVE 状态校验）
│   ├── application/
│   │   ├── usecase/LoginByPhoneSmsUseCase.java   ← **新建**
│   │   ├── command/LoginByPhoneSmsCommand.java   ← **新建** record { phone, code }
│   │   └── result/LoginByPhoneSmsResult.java     ← **新建** record { accountId, accessToken, refreshToken }
│   ├── infrastructure/
│   │   ├── persistence/AccountRepositoryImpl.java ← 改：实现 updateLastLoginAt
│   │   └── persistence/AccountJpaEntity.java      ← 改：加 lastLoginAt 字段 + @Column
│   └── web/
│       ├── controller/AuthController.java        ← **新建**（与既有 AccountRegisterController 平行；4 endpoints 在 Phase 1.1-1.4 内陆续填）
│       ├── request/LoginByPhoneSmsRequest.java   ← **新建**
│       └── response/LoginResponse.java           ← **新建**（与 register 复用 RegisterResponse 字段一致：accountId/accessToken/refreshToken；考虑抽 mbw-shared.api.AuthTokenResponse 共享）
│
└── src/main/resources/db/migration/account/
    └── V3__add_account_last_login_at.sql        ← **新建**

mbw-shared/                                       ← 跨模块 / 跨 use case 公共
├── api/sms/                                      ← 复用 SmsCodeService（register 已建）
└── api/error/                                    ← 复用 RateLimitedException 等
```

## Domain Design

### Account 聚合根扩展

```java
public final class Account {
  private final AccountId id;
  private final PhoneNumber phone;
  private final AccountStatus status;
  private final Instant createdAt;
  private final Instant updatedAt;
  private final Instant lastLoginAt;   // ← 新增；nullable（首次登录前 NULL）

  // ... existing methods ...

  /** 不变式：仅 ACTIVE 账号可标记登录时间。 */
  public Account markLoggedIn(Instant now) {
    if (status != AccountStatus.ACTIVE) {
      throw new IllegalStateException("Cannot login non-ACTIVE account: " + status);
    }
    return new Account(id, phone, status, createdAt, now, /* lastLoginAt= */ now);
  }
}
```

### AccountRepository 接口

```java
public interface AccountRepository {
  Optional<Account> findByPhone(PhoneNumber phone);    // ← 复用
  // ...
  void updateLastLoginAt(AccountId accountId, Instant lastLoginAt);  // ← 新增
}
```

实现见 `AccountRepositoryImpl`：转 JPA `UPDATE account SET last_login_at = ?, updated_at = ? WHERE id = ?`，单条更新无并发问题。

### AccountStateMachine（复用 + 微调）

```java
public boolean canLogin(Account account) {
  return account.status() == AccountStatus.ACTIVE;
  // FROZEN / ANONYMIZED / 等未来状态全部返回 false → INVALID_CREDENTIALS（防枚举）
}
```

## UseCase: `LoginByPhoneSmsUseCase`

```java
@Service
public class LoginByPhoneSmsUseCase {
  private final RateLimitService rateLimitService;
  private final SmsCodeService smsCodeService;
  private final AccountRepository accountRepository;
  private final TokenIssuer tokenIssuer;
  private final AccountStateMachine stateMachine;
  private final TimingDefenseExecutor timingDefenseExecutor;  // ← 复用 register 的 dummy bcrypt 入口

  @Transactional(rollbackFor = Throwable.class)
  public LoginByPhoneSmsResult execute(LoginByPhoneSmsCommand command) {
    return timingDefenseExecutor.execute(() -> {
      // 1. 限流（FR-005）
      rateLimitService.consumeOrThrow("sms:" + command.phone(), Duration.ofSeconds(60));
      rateLimitService.consumeOrThrow("login:" + command.phone(), Duration.ofHours(24));  // 失败后 5 次锁

      // 2. 验证码消费（FR-001 复用 register 的 sms_code:<phone> key）
      AttemptOutcome outcome = smsCodeService.verify(command.phone(), command.code());
      if (outcome != AttemptOutcome.SUCCESS) {
        throw new InvalidCredentialsException();  // 错码 / 过期 / 已用 → 统一返回（FR-006）
      }

      // 3. 查 Account + 状态校验
      Optional<Account> accountOpt = accountRepository.findByPhone(command.phone());
      if (accountOpt.isEmpty() || !stateMachine.canLogin(accountOpt.get())) {
        throw new InvalidCredentialsException();  // 未注册 / FROZEN / ANONYMIZED → 字节级一致（FR-006）
      }
      Account account = accountOpt.get();

      // 4. Token 签发（FR-007；refresh token 持久化在 Phase 1.3 统一回填）
      String accessToken = tokenIssuer.signAccess(account.id());
      String refreshToken = tokenIssuer.signRefresh();

      // 5. 更新 last_login_at（FR-004 + FR-010 同事务）
      Instant now = Instant.now();
      accountRepository.updateLastLoginAt(account.id(), now);

      return new LoginByPhoneSmsResult(account.id().value(), accessToken, refreshToken);
    });
  }
}
```

**TimingDefenseExecutor 复用要点**：register-by-phone 已建的 `TimingDefenseExecutor` 抽象成跨 use case 通用 wrapper，本 use case 直接注入。executor 包装逻辑：所有路径（成功 / 未注册 / 码错 / 限流 / token 失败）pad 到 P95 ≤ 600ms 一致（per SC-001 + SC-003）。

## Web Layer

### AuthController（新建，Phase 1.1-1.4 共用）

```java
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final LoginByPhoneSmsUseCase loginByPhoneSmsUseCase;
  // 1.2: + LoginByPasswordUseCase
  // 1.3: + RefreshTokenUseCase
  // 1.4: + LogoutAllSessionsUseCase

  @PostMapping("/login-by-phone-sms")
  public ResponseEntity<LoginResponse> loginByPhoneSms(@Valid @RequestBody LoginByPhoneSmsRequest request) {
    var result = loginByPhoneSmsUseCase.execute(request.toCommand());
    return ResponseEntity.ok(LoginResponse.from(result));
  }

  // FR-009 新增 sms-codes endpoint with purpose 字段（如 register 的 endpoint 已不可扩展，新建 /api/v1/auth/sms-codes）
}
```

### `/sms-codes` endpoint with purpose 字段

**决策**：复用既有 `/api/v1/accounts/sms-codes`（register 已建）扩展 purpose 字段：

- 既有 endpoint 接口：`POST /api/v1/accounts/sms-codes` `{phone}`
- 新接口：`POST /api/v1/accounts/sms-codes` `{phone, purpose}`，**purpose 默认 "register"**（向后兼容）
- 接口 Springdoc 注解 + spec 描述更新

**或备选**：新建 `POST /api/v1/auth/sms-codes` `{phone, purpose}` 与 `/api/v1/accounts/sms-codes` 并行（共享 RequestSmsCodeUseCase）。**plan 默认走扩展现有 endpoint 方案**（向后兼容更轻），若 Phase 1.4 实施时发现耦合复杂再切独立 endpoint。

### Spec.md FR-009 实现

`RequestSmsCodeUseCase` 接受 `purpose` 参数，分发模板：

| purpose | phone 状态 | 模板 | SMS 内容 |
|---|---|---|---|
| register | 未注册 | Template A | 验证码 6 位 |
| register | 已注册 | Template B | 注册冲突提示，无码 |
| login | 已注册 | Template A | 验证码 6 位（与 register 未注册路径同模板）|
| login | 未注册 | **Template C（新增）** | 登录失败提示，无码 |

**注**：Template A 在 register/未注册 与 login/已注册 共享，因为发码 + 用户要看到 6 位数字 → 内容一致。代码层面 Template A 仅一个常量 / 一个阿里云模板 ID。

## Test Strategy

| 层 | 测试类 | 覆盖 |
|---|---|---|
| Domain unit | `AccountStateMachineTest`（已有，加 `canLogin` 分支）| canLogin 对 ACTIVE/FROZEN/ANONYMIZED 状态返回 |
| Domain unit | `AccountTest`（已有，加 `markLoggedIn` 分支）| 新方法对 non-ACTIVE 抛异常；ACTIVE 返回 with-updated lastLoginAt |
| App unit | `LoginByPhoneSmsUseCaseTest`（新建）| Mockito mock 5 依赖；覆盖 6 分支（success / 限流 / 码错 / 未注册 / FROZEN / token 失败）|
| Repo IT | `AccountRepositoryImplIT`（已有，加 updateLastLoginAt 测）| Testcontainers PG，断言列更新 + updated_at 同步 |
| Web IT | `AuthControllerLoginByPhoneSmsIT`（新建）| `@WebMvcTest`，mock UseCase；覆盖 200 / 400 / 401 / 429 / 503 |
| E2E | `LoginByPhoneSmsE2EIT`（新建，Testcontainers PG + Redis + Mock SMS）| 9 个 Acceptance Scenarios（User Stories 1/2/3）+ SC-002 / SC-004 |
| Timing | `LoginByPhoneSmsTimingDefenseIT`（新建）| 1000 次循环对比已注册 vs 未注册 P95 时延差 ≤ 50ms (SC-003) |
| Cross-use-case | `CrossUseCaseEnumerationDefenseIT`（新建）| register 已注册 vs login 未注册 vs login 已注册码错 三场景响应字节级一致 (SC-005)|
| ArchUnit | 既有 `ModuleStructureTest` | 新代码不破坏边界（依赖方向 / 跨模块只走 api 包）|

## DB Schema

### Migration: `V3__add_account_last_login_at.sql`

```sql
-- per spec.md FR-012, expand-only schema 变更
ALTER TABLE account.account
  ADD COLUMN last_login_at TIMESTAMP WITH TIME ZONE NULL;

-- 索引：按"近 N 天活跃用户"统计 use case M3+ 引入时再加 idx_account_last_login_at
-- M1.2 不预添加避免索引膨胀
```

回滚策略（M1 阶段允许回滚）：

```sql
-- ROLLBACK: V3__rollback.sql
ALTER TABLE account.account DROP COLUMN last_login_at;
```

## Phasing & Out of Scope

本 use case 仅完成：

- ✅ Domain: `Account.lastLoginAt` + `AccountRepository.updateLastLoginAt`
- ✅ Application: `LoginByPhoneSmsUseCase`
- ✅ Infrastructure: `AccountRepositoryImpl.updateLastLoginAt` + JPA entity 加列
- ✅ Web: `AuthController` 新建 + `/login-by-phone-sms` endpoint + `RequestSmsCodeUseCase` purpose 扩展
- ✅ Migration: `V3__add_account_last_login_at.sql`
- ✅ Test: 单测 + IT + E2E + Timing + Cross-use-case Defense
- ❌ refresh token 持久化（推 Phase 1.3）
- ❌ Account FROZEN / ANONYMIZED 状态机（推后续 use case；本 use case 仅校验 ACTIVE）
- ❌ Template C 阿里云模板审批（流程中，未审下来时 fallback 不发任何 SMS + pad time）
- ❌ Idempotency-Key（M3 引入，与 register Out of Scope 一致）

## Verification

```bash
./mvnw -pl mbw-account verify          # mbw-account 全测试，含 IT
./mvnw -pl mbw-app -Dtest=ModuleStructureTest test  # ArchUnit 边界守护

curl -X POST http://localhost:8080/v3/api-docs > /tmp/spec.json
# 期望：spec.json 含 POST /api/v1/auth/login-by-phone-sms 路径
```
