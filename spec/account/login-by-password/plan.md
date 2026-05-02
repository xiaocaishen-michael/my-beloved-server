# Implementation Plan: Login by Password

**Use case**: `login-by-password`
**Module**: `mbw-account`
**Spec**: [`./spec.md`](./spec.md)
**Builds on**: [`../register-by-phone/plan.md`](../register-by-phone/plan.md) + [`../login-by-phone-sms/plan.md`](../login-by-phone-sms/plan.md)

> 设计原则：**最大化复用 1.1 + register 已落基础设施**。本 use case 仅新增 1 个 UseCase + 1 个 endpoint + 扩展 TimingDefenseExecutor（加 BCrypt verify 变体）。**无 schema 变更**——password_hash 列在 V2 已建。

## Constitution Check

| 原则 | 本 use case 落实 |
|---|---|
| Modular Monolith | 工作仅在 `mbw-account`；不引入跨模块依赖 |
| DDD 5 层 | 复用 domain `Account` / `PasswordCredential` / `TokenIssuer` / `AccountStateMachine`；application 新增 `LoginByPasswordUseCase`；web 扩展 `AuthController`（1.1 已建） |
| TDD 强制 | 红绿循环全覆盖；UseCase 单测 + Testcontainers IT + Timing Defense IT |
| Conventional Commits | `docs(account): ...` (本 PR) + `feat(account): ...`（impl PR）|
| ArchUnit 边界守护 | 新代码不破坏既有边界 |
| DB schema 隔离 | **无 schema 变更**（password_hash 列在 V2 已建） |
| Expand-migrate-contract | **不适用**（无 schema 变更） |

## Project Structure

```text
mbw-account/
├── src/main/java/com/mbw/account/
│   ├── api/                                    ← 无新对外接口
│   ├── domain/
│   │   ├── service/PasswordHasher.java         ← 复用（register 已建，封装 BCrypt cost=12）
│   │   ├── service/TimingDefenseExecutor.java  ← **改**：加 BCrypt verify wrapper 变体
│   │   ├── repository/CredentialRepository.java ← 复用（register 已建，加 findByAccountIdAndType 方法如未实装）
│   │   └── model/PasswordCredential.java       ← 复用（register 已建）
│   ├── application/
│   │   ├── usecase/LoginByPasswordUseCase.java ← **新建**
│   │   ├── command/LoginByPasswordCommand.java ← **新建** record { phone, password }
│   │   └── result/LoginByPasswordResult.java   ← **新建** record { accountId, accessToken, refreshToken }
│   ├── infrastructure/                         ← 无新代码（CredentialRepositoryImpl 已建）
│   └── web/
│       ├── controller/AuthController.java      ← **改**：加 loginByPassword 方法（1.1 已建该 controller）
│       ├── request/LoginByPasswordRequest.java ← **新建**
│       └── response/LoginResponse.java         ← 复用（1.1 已建）
└── （无新 Flyway migration）
```

## Domain Design

### TimingDefenseExecutor 扩展

```java
public final class TimingDefenseExecutor {
  // 既有 register / login-sms 用的：
  public <T> T execute(Supplier<T> action) { ... }  // 入口跑 dummy bcrypt + action

  // login-by-password 新增：
  public <T> T executeWithBCryptVerify(
      String userInput,
      Function<String, String> hashLookup,  // 根据 input 获取 hash（账号存在则真 hash，否则 dummy hash）
      Supplier<T> onMatch,                  // BCrypt verify 通过后执行
      Supplier<T> onMismatch                // verify 失败 / hash 缺失 时执行（防枚举返回 INVALID_CREDENTIALS）
  ) {
    String hash = hashLookup.apply(userInput);  // 必返回非空 hash（不存在则返 DUMMY_HASH）
    boolean matches = passwordEncoder.matches(userInput, hash);
    return matches ? onMatch.get() : onMismatch.get();
  }
}
```

**关键设计**：`hashLookup` 永不返回 null（不存在路径返 `DUMMY_HASH`），保证 BCrypt 计算永远执行 → 时延一致。

### CredentialRepository（复用 + 可能扩展）

```java
public interface CredentialRepository {
  // ...
  Optional<PasswordCredential> findPasswordCredentialByAccountId(AccountId accountId);
  void updateLastUsedAt(CredentialId credentialId, Instant lastUsedAt);  // ← 可选；视后续 use case 决定
}
```

## UseCase: `LoginByPasswordUseCase`

```java
@Service
public class LoginByPasswordUseCase {
  private final RateLimitService rateLimitService;
  private final AccountRepository accountRepository;
  private final CredentialRepository credentialRepository;
  private final TokenIssuer tokenIssuer;
  private final AccountStateMachine stateMachine;
  private final TimingDefenseExecutor timingDefenseExecutor;

  @Transactional(rollbackFor = Throwable.class)
  public LoginByPasswordResult execute(LoginByPasswordCommand command) {
    // 1. 限流（FR-007）
    rateLimitService.consumeOrThrow("login:" + command.phone(), Duration.ofHours(24));     // 共享 1.1 失败 bucket
    rateLimitService.consumeOrThrow("auth:" + command.clientIp(), Duration.ofHours(24));   // 新增 IP bucket

    // 2. 入口级 BCrypt verify（FR-009 - 时延对齐 + 业务校验二合一）
    return timingDefenseExecutor.executeWithBCryptVerify(
        command.password(),
        userPassword -> {
          // hashLookup：尝试找账号 + PasswordCredential，找不到返 dummy hash
          Optional<Account> accountOpt = accountRepository.findByPhone(command.phone());
          if (accountOpt.isEmpty() || !stateMachine.canLogin(accountOpt.get())) {
            return TimingDefenseExecutor.DUMMY_HASH;  // 防枚举：未注册 / FROZEN → 用 dummy hash
          }
          var credentialOpt = credentialRepository.findPasswordCredentialByAccountId(accountOpt.get().id());
          return credentialOpt.map(PasswordCredential::passwordHash).orElse(TimingDefenseExecutor.DUMMY_HASH);
        },
        () -> {
          // onMatch：verify 通过，签 token + 更新 last_login_at
          Account account = accountRepository.findByPhone(command.phone()).orElseThrow();
          String accessToken = tokenIssuer.signAccess(account.id());
          String refreshToken = tokenIssuer.signRefresh();
          accountRepository.updateLastLoginAt(account.id(), Instant.now());
          return new LoginByPasswordResult(account.id().value(), accessToken, refreshToken);
        },
        () -> { throw new InvalidCredentialsException(); }  // onMismatch：所有失败统一返
    );
  }
}
```

**注意**：`hashLookup` 内的 `findByPhone` + `findPasswordCredentialByAccountId` 两次 DB 查询会在 happy path 跑 2 次（hashLookup + onMatch 各一）—— 这是**故意**的，避免传引用 / 缓存复杂化时延一致性。如果性能敏感，可优化为单次查 + 缓存（M2 复评）。

## Web Layer

### AuthController 扩展（1.1 已建）

```java
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final LoginByPhoneSmsUseCase loginByPhoneSmsUseCase;     // 1.1
  private final LoginByPasswordUseCase loginByPasswordUseCase;     // 1.2 新增
  // 1.3: + RefreshTokenUseCase
  // 1.4: + LogoutAllSessionsUseCase

  @PostMapping("/login-by-password")
  public ResponseEntity<LoginResponse> loginByPassword(@Valid @RequestBody LoginByPasswordRequest request, HttpServletRequest httpRequest) {
    var result = loginByPasswordUseCase.execute(request.toCommand(httpRequest.getRemoteAddr()));
    return ResponseEntity.ok(LoginResponse.from(result));
  }
}
```

`HttpServletRequest.getRemoteAddr()` 用于 `auth:<ip>` 限流；考虑反向代理场景（X-Forwarded-For），生产部署需配 `server.forward-headers-strategy=native`（Spring Boot），与既有 register-by-phone IP 提取一致。

## Test Strategy

| 层 | 测试类 | 覆盖 |
|---|---|---|
| Domain unit | `TimingDefenseExecutorTest`（已有，加 `executeWithBCryptVerify` 分支）| match / mismatch / hashLookup 返回 dummy → mismatch 路径 |
| App unit | `LoginByPasswordUseCaseTest`（新建）| Mockito mock 6 依赖；覆盖 7 分支（success / 限流 / 密码错 / 未注册 / 未设密码 / FROZEN / token 失败）|
| Web IT | `AuthControllerLoginByPasswordIT`（新建）| `@WebMvcTest`，mock UseCase；覆盖 200 / 400 / 401 / 429 / 500 |
| E2E | `LoginByPasswordE2EIT`（新建，Testcontainers PG + Redis）| 12 个 Acceptance Scenarios（User Stories 1-4）+ SC-002 / SC-004 |
| Timing | `LoginByPasswordTimingDefenseIT`（新建）| 1000 次循环，3 场景（已设密码 + 错密码 / 未设密码 + 任意 / 未注册 + 任意）P95 时延差 ≤ 50ms (SC-003) |
| Cross-use-case | 扩展 `CrossUseCaseEnumerationDefenseIT`（1.1 引入）| 加 login-by-password 三场景断言响应字节级一致 (SC-005) |
| ArchUnit | 既有 `ModuleStructureTest` | 新代码不破坏边界 |

## DB Schema

无变更。复用 V2 已建 credential 表（password_hash 列存 BCrypt hash）+ V3 已建 account.last_login_at 列（Phase 1.1 引入）。

## Phasing & Out of Scope

本 use case 完成：

- ✅ Domain: `TimingDefenseExecutor.executeWithBCryptVerify` 扩展
- ✅ Application: `LoginByPasswordUseCase` + Command + Result
- ✅ Web: `AuthController.loginByPassword` 方法 + Request DTO
- ✅ Test: 单测 + IT + E2E + Timing + Cross-use-case Defense 扩展
- ❌ refresh token 持久化（推 Phase 1.3）
- ❌ set-password / forgot-password use case（M1.3）
- ❌ 2FA / captcha 触发 / 失败 metric（M1.3+ / M3+）

## Verification

```bash
./mvnw -pl mbw-account verify
./mvnw -pl mbw-app -Dtest=ModuleStructureTest test

curl -X POST http://localhost:8080/v3/api-docs > /tmp/spec.json
# 期望：spec.json 含 POST /api/v1/auth/login-by-password 路径
```
