# Implementation Plan: Logout All Sessions

**Use case**: `logout-all`
**Module**: `mbw-account`（spec 在 `specs/auth/` 因 token lifecycle 跨登录方式横切）
**Spec**: [`./spec.md`](./spec.md)
**Builds on**: [`../refresh-token/plan.md`](../refresh-token/plan.md)（必需 — 本 use case 调用 1.3 引入的 `RefreshTokenRepository.revokeAllForAccount`）

> 本 use case 是 Phase 1 中**最简单**的一个 — 无新表 + 无新 domain entity + 无 retrofit；仅 1 个新 endpoint + 1 个新 UseCase + 复用 1.3 既有 repo 方法。

## Constitution Check

| 原则 | 本 use case 落实 |
|---|---|
| Modular Monolith | 工作仅在 `mbw-account`；spec 组织在 `specs/auth/` 仅作命名约定 |
| DDD 5 层 | 无新 domain；application 新增 `LogoutAllSessionsUseCase`；web 扩展 `AuthController` |
| TDD 强制 | 红绿循环全覆盖；UseCase 单测 + Testcontainers IT |
| Conventional Commits | `docs(auth): ...` (本 PR) + `feat(auth): ...`（impl PR） |
| ArchUnit 边界守护 | 新代码不破坏既有边界 |
| DB schema 隔离 | 无 schema 变更；复用 1.3 `account.refresh_token` 表 |
| Expand-migrate-contract | 不适用（无 schema 变更） |

## Project Structure

```text
mbw-account/
├── src/main/java/com/mbw/account/
│   ├── application/
│   │   ├── usecase/LogoutAllSessionsUseCase.java   ← **新建**
│   │   ├── command/LogoutAllSessionsCommand.java   ← **新建** record { accountId, clientIp }
│   │   └── result/LogoutAllSessionsResult.java     ← **新建** record { revokedCount }（仅 logging 用，不返客户端）
│   └── web/
│       └── controller/AuthController.java          ← **改**：加 logoutAll 方法（1.1 / 1.2 / 1.3 已扩展该 controller）
└── （无新 migration / 无新 domain / 无新 infrastructure）
```

**注**：本 use case **零新 domain 类** —— 仅调 1.3 既有 `RefreshTokenRepository.revokeAllForAccount(accountId, now)` 一行。

## Domain Design

**无新 domain 类**。本 use case 是纯 application 层编排：

- 入参：accountId（从 access token `sub` claim 解出）+ clientIp（用于限流）
- 行为：调 `refreshTokenRepository.revokeAllForAccount(accountId, now)`
- 出参：affected rows count（仅 logging，不返客户端）

domain 层既有不变（Account 状态机不影响 logout-all；revoke 是清理动作对所有 status 都执行）。

## UseCase: `LogoutAllSessionsUseCase`

```java
@Service
public class LogoutAllSessionsUseCase {
  private static final Logger log = LoggerFactory.getLogger(LogoutAllSessionsUseCase.class);

  private final RateLimitService rateLimitService;
  private final RefreshTokenRepository refreshTokenRepository;

  @Transactional(rollbackFor = Throwable.class)
  public LogoutAllSessionsResult execute(LogoutAllSessionsCommand command) {
    // 1. 限流（FR-006）
    rateLimitService.consumeOrThrow(
        "logout-all:" + command.accountId().value(), Duration.ofSeconds(60));
    rateLimitService.consumeOrThrow(
        "logout-all:" + command.clientIp(), Duration.ofSeconds(60));

    // 2. 批量 revoke
    Instant now = Instant.now();
    int revokedCount = refreshTokenRepository.revokeAllForAccount(command.accountId(), now);

    log.info(
        "logout-all completed: accountId={} revokedCount={}",
        command.accountId().value(),
        revokedCount);

    return new LogoutAllSessionsResult(revokedCount);
  }
}
```

**关键不变性**：

- 不校验 Account 是否存在 / 是否 ACTIVE —— 即使账号被冻结 / 删除，revoke 依然执行（per spec.md edge cases）
- 影响 0 行不抛异常 —— 幂等
- 仅 1 个 DB 操作（UPDATE），事务回滚等价于 noop（保留 `@Transactional` 是规范一致性，便于未来加多步操作）

## Web Layer

### AuthController 扩展（1.1 / 1.2 / 1.3 已建）

```java
@PostMapping("/logout-all")
public ResponseEntity<Void> logoutAll(
    @AuthenticationPrincipal AccountId accountId,  // Spring Security 解出
    HttpServletRequest httpRequest) {
  logoutAllSessionsUseCase.execute(
      new LogoutAllSessionsCommand(accountId, httpRequest.getRemoteAddr()));
  return ResponseEntity.noContent().build();  // 204
}
```

**说明**：

- `@AuthenticationPrincipal AccountId accountId` 由既有 Spring Security `JwtAuthenticationFilter` 注入（per A-013）
- 无 request body —— accountId 来自 access token，clientIp 来自 HttpServletRequest
- 返回 `Void` + 204（per FR-002）
- Springdoc 注解齐（含 OpenAPI 描述 + 错误码 examples）

### Spring Security 配置

`/api/v1/auth/logout-all` 路径需 `.authenticated()` 配置（与既有受保护 endpoint 一致；其他 auth endpoint 如 login / refresh-token / sms-codes 都是 `.permitAll()`）。

```java
// SecurityConfig（既有，扩展 matcher）
http.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/auth/logout-all").authenticated()
    .requestMatchers("/api/v1/auth/**").permitAll()
    // ...
);
```

## Test Strategy

| 层 | 测试类 | 覆盖 |
|---|---|---|
| App unit | `LogoutAllSessionsUseCaseTest`（**新建**）| Mockito mock 2 依赖（RateLimitService / RefreshTokenRepository）；覆盖 4 分支：happy path / 限流 account / 限流 IP / repo 异常回滚 |
| Web IT | `AuthControllerLogoutAllIT`（**新建**）| `@WebMvcTest(AuthController.class)`；覆盖 200/204 / 401 (4 鉴权失败场景) / 429 / 500 |
| E2E | `LogoutAllE2EIT`（**新建**，Testcontainers PG + Redis）| User Stories 1-3 全部 9 个 Acceptance Scenarios + Edge Cases（账号无 token / FROZEN / 不存在）+ SC-004 / SC-005 |
| Concurrency | `LogoutAllConcurrencyIT`（**新建**）| 同账号 10 并发 logout-all 全 204 + DB 内 UPDATE 影响行数收敛 (SC-003) |
| Multi-device | `LogoutAllE2EIT` 内 `should_revoke_all_three_devices_when_user_has_three_active_sessions()` (SC-005) | 注册 + 3 次登录拿不同 refresh token + logout-all + 三个 refresh-token 调用全 401 |
| Cross-use-case | 既有 `CrossUseCaseEnumerationDefenseIT` | 加 logout-all 鉴权失败的 INVALID_CREDENTIALS 断言响应字节级一致 |
| ArchUnit | 既有 `ModuleStructureTest` | 新代码不破坏边界 |

## DB Schema

**无变更**。本 use case 复用 1.3 引入的 `account.refresh_token` 表 + partial index `idx_refresh_token_account_id_active WHERE revoked_at IS NULL`。

partial index 加速效果：`UPDATE ... WHERE account_id = ? AND revoked_at IS NULL` 直接命中 index range scan，避免全表扫；M3+ 即使表内累积百万 revoked 行也不影响查询性能。

## Phasing & Out of Scope

本 use case 完成：

- ✅ Application: LogoutAllSessionsUseCase + Command + Result
- ✅ Web: AuthController.logoutAll 方法 + Spring Security `.authenticated()` 配置
- ✅ Test: UseCase 单测 + Web IT + E2E + Concurrency + Multi-device + Cross-use-case 扩展
- ❌ `/logout`（仅当前设备）endpoint（per CL-002，M3+）
- ❌ access token 立即 kick / redis 黑名单（per CL-001，M3+）
- ❌ logout-all 审计日志 / push 通知 / SIEM 告警（M3+）

## Verification

```bash
./mvnw -pl mbw-account verify
./mvnw -pl mbw-app -Dtest=ModuleStructureTest test

curl -X POST http://localhost:8080/v3/api-docs > /tmp/spec.json
# 期望：spec.json 含 POST /api/v1/auth/logout-all 路径 + security: bearerAuth
```

## 与 Phase 1.3 refresh-token 的协同

本 use case **完全依赖** 1.3：

| 1.3 产出 | 本 use case 使用 |
|---|---|
| `account.refresh_token` 表 | UPDATE WHERE account_id = ? AND revoked_at IS NULL |
| partial index `idx_refresh_token_account_id_active` | 加速批量 UPDATE 查询 |
| `RefreshTokenRepository.revokeAllForAccount(accountId, now)` 方法 | UseCase 内一行调用 |

**结论**：本 use case impl PR 必须在 1.3 impl PR 合并后开（编译依赖）。tasks PR（本 PR）可独立合并。

## 与 Phase 4 frontend 的协同

frontend Phase 4 主页 `(app)/index.tsx` 加"退出所有设备"按钮：

```typescript
// apps/native/app/(app)/index.tsx
const onLogoutAll = async () => {
  if (await confirm('确定要退出所有设备？所有登录会话都将失效，需要重新登录')) {
    await authApi.logoutAll();         // 调本 endpoint，204
    authStore.clearSession();          // 清当前设备 store（access token 在 ≤ 15min 内仍有效但 store 空 = UI 不发请求 = 等价 logout）
    router.replace('/(auth)/login');   // 跳登录页
  }
};
```

frontend 不感知 access token 的 15min 残留窗口（per CL-001）—— 清 store 后业务请求停止，等价于立即退出体验。
