# Implementation Tasks: Login by Password

**Use case**: `login-by-password`
**Spec**: [`./spec.md`](./spec.md)
**Plan**: [`./plan.md`](./plan.md)
**Estimated total**: ~6-8h（少于 1.1 因为无 schema 变更 + 大量复用）

> **TDD 节奏**：每个 task 内严格红绿循环。任务标签同 1.1 体例。

## Critical Path

### T0 [Domain] TimingDefenseExecutor.executeWithBCryptVerify 扩展

**File**: `mbw-account/src/main/java/com/mbw/account/domain/service/TimingDefenseExecutor.java`（已有，扩展）

**Logic**: per plan.md §Domain Design，加 `executeWithBCryptVerify(userInput, hashLookup, onMatch, onMismatch)` 方法。`hashLookup` 永不返 null（账号不存在时返 `DUMMY_HASH`）。

**Test**: `TimingDefenseExecutorTest`（已有，扩展）

- `should_invoke_onMatch_when_password_matches_hash()`
- `should_invoke_onMismatch_when_password_does_not_match_hash()`
- `should_invoke_onMismatch_when_hashLookup_returns_dummy_hash()`
- `should_have_constant_time_for_match_vs_mismatch_paths()` (timing assertion 在该 unit test 难做精准，主要靠 T6 IT 测)

**Dependencies**: 无（独立 domain 扩展）。

---

### T1 [App] LoginByPasswordCommand + LoginByPasswordResult

**Files**:

- `mbw-account/src/main/java/com/mbw/account/application/command/LoginByPasswordCommand.java`
- `mbw-account/src/main/java/com/mbw/account/application/result/LoginByPasswordResult.java`

**Logic**:

- `LoginByPasswordCommand`: `record(PhoneNumber phone, String password, String clientIp)`
- `LoginByPasswordResult`: `record(long accountId, String accessToken, String refreshToken)`（与 1.1 LoginByPhoneSmsResult 字段一致 — 考虑提取共享 record `mbw-account.application.result.AuthResult`，本 PR 暂时各自一份避免过早抽象）

**Test**: 纯 record，TDD 例外。

**Dependencies**: 无。

---

### T2 [App] LoginByPasswordUseCase

**File**: `mbw-account/src/main/java/com/mbw/account/application/usecase/LoginByPasswordUseCase.java`

**Logic**: per plan.md § UseCase 段，6 步执行链（限流 → 入口级 BCrypt verify wrapper → 查 Account + Credential → 状态校验 → Token 签发 → 写 last_login_at）+ `@Transactional`。

**Test**: `LoginByPasswordUseCaseTest`（**新建**），Mockito mock 6 依赖。

**7 分支覆盖（红绿循环逐条）**：

- `should_return_tokens_when_phone_password_match_active_account()` — happy path
- `should_throw_RateLimitedException_when_login_phone_throttled()` — login:`<phone>` 限流
- `should_throw_RateLimitedException_when_auth_ip_throttled()` — auth:`<ip>` 限流
- `should_throw_InvalidCredentialsException_when_password_wrong()` — 密码错
- `should_throw_InvalidCredentialsException_when_phone_not_registered()` — 未注册防枚举（hashLookup 返 dummy）
- `should_throw_InvalidCredentialsException_when_password_not_set()` — 未设密码防枚举（hashLookup 返 dummy）
- `should_throw_InvalidCredentialsException_when_account_FROZEN()` — 状态机
- `should_throw_when_token_signing_fails()` — TokenIssuer 异常 → 事务回滚

**Dependencies**: T0（TimingDefenseExecutor 扩展）+ T1。

---

### T3 [Web] LoginByPasswordRequest DTO

**File**: `mbw-account/src/main/java/com/mbw/account/web/request/LoginByPasswordRequest.java`

**Logic**:

- record(`@Pattern(...) String phone, @NotBlank String password`)
- `toCommand(String clientIp)` 方法封装 PhoneNumber 创建 + Command record

**Test**: Validation 单测：

- `should_reject_when_phone_format_invalid()` (Pattern 失败 → 400)
- `should_reject_when_password_blank()` (NotBlank 失败 → 400)

**Dependencies**: T1 完成。

---

### T4 [Web] AuthController.loginByPassword 方法

**File**: `mbw-account/src/main/java/com/mbw/account/web/controller/AuthController.java`（1.1 新建，本 PR 扩展）

**Logic**: per plan.md § Web Layer。Springdoc 注解齐（含 OpenAPI 描述 + 错误码 examples）。`HttpServletRequest.getRemoteAddr()` 提取 client IP。

**Test**: `AuthControllerLoginByPasswordIT`（**新建**），`@WebMvcTest(AuthController.class)`：

- `should_return_200_when_valid_credentials()` — happy path
- `should_return_400_when_invalid_phone_format()` — Validation
- `should_return_400_when_password_blank()` — Validation
- `should_return_401_when_InvalidCredentialsException_thrown()` — 错凭据 / 未注册 / 未设密码 / FROZEN
- `should_return_429_when_RateLimitedException_thrown()` — 限流（含 login:`<phone>` 与 auth:`<ip>`）
- `should_return_500_when_unexpected_exception_thrown()` — 兜底

**Dependencies**: T2, T3 完成。

---

### T5 [E2E] LoginByPasswordE2EIT

**File**: `mbw-account/src/test/java/com/mbw/account/web/LoginByPasswordE2EIT.java`（**新建**）

**Logic**: Testcontainers PG + Redis。覆盖 spec.md 12 个 Acceptance Scenarios（User Stories 1-4）+ SC-002（100 并发不同账号 0 错）+ SC-004（限流 3 规则）。

**Test cases**：

- US1 (3 个 AS): happy path + 错密码 + 多设备
- US2 (3 个 AS): 未设密码防枚举（密码字节级一致 + 强度不暴露 + 时延一致）
- US3 (2 个 AS): 未注册防枚举 + 跨 endpoint 一致
- US4 (3 个 AS): 24h 5 次锁 + 跨 sms / password 共享 bucket + auth:`<ip>` IP 限流
- SC-002: 100 个不同已设密码账号并发 login，0 错 + token count == 请求数
- SC-004: login:`<phone>` 24h 5 次 / auth:`<ip>` 24h 100 次

**Fixture**: BeforeEach 起空 PG schema + 预注册 N 个 ACTIVE 账号（部分设密码部分未设）；Redis 容器与 register / 1.1 共享。

**Dependencies**: T0-T4 完成。

---

### T6 [Performance] LoginByPasswordTimingDefenseIT

**File**: `mbw-account/src/test/java/com/mbw/account/web/LoginByPasswordTimingDefenseIT.java`（**新建**）

**Logic**: 1000 次循环对比 3 场景 P95 时延差，断言 ≤ 50ms（SC-003）：

1. 已设密码 + 错密码（`BCrypt.matches → false`）
2. 未设密码（`hashLookup 返 dummy hash → BCrypt.matches → false`）
3. 未注册（`hashLookup 返 dummy hash → BCrypt.matches → false`）

预期：3 场景 P95 都接近（差 ≤ 50ms），因为入口都跑同一次 BCrypt verify (cost=12)。

**Test cases**：

- `should_have_p95_diff_under_50ms_for_password_set_vs_not_set_vs_not_registered()`
- 测试方法：N 次（recommend 1000）调 `/login-by-password` 各场景，记录 P95；3 场景两两对比 P95 差。
- 失败 retry-on-failure 最多 3 次取最优 P95（per analysis.md A8 of 1.1，避免 GHA runner 抖动假阳性）

**Dependencies**: T0-T4 完成（与 T5 可并行）。

---

### T7 [E2E] CrossUseCaseEnumerationDefenseIT 扩展

**File**: `mbw-account/src/test/java/com/mbw/account/web/CrossUseCaseEnumerationDefenseIT.java`（1.1 引入，本 PR 扩展）

**Logic**: 扩展 1.1 引入的测试，加 login-by-password 三场景，断言与既有 register / login-by-phone-sms 三场景**两两组合 9 个对比**响应字节级一致。

**Test cases**：

- 既有 (1.1 引入)：register 已注册 vs login-by-phone-sms 未注册 vs login-by-phone-sms 错码 → 字节级一致
- 新增 (1.2 PR)：login-by-password 已注册错密码 vs login-by-password 未注册 vs login-by-password 未设密码 → 字节级一致
- 新增交叉断言：register 已注册 vs login-by-password 错密码 → 字节级一致（HTTP 401 + INVALID_CREDENTIALS + ProblemDetail body 完全相同）

**Dependencies**: T5 完成。

---

### T8 [Contract] OpenAPI spec 验证

**File**: 无新文件；本任务验证 Springdoc 自动生成的 OpenAPI spec 含新 endpoint。

**Logic**:

- 起本地 Spring Boot：`./mvnw spring-boot:run -pl mbw-app`
- `curl http://localhost:8080/v3/api-docs > /tmp/spec.json`
- 断言：`/api/v1/auth/login-by-password` 路径存在 + Request schema 含 password 字段

**Dependencies**: T4 完成。

---

## Parallel Opportunities

- **T0 / T1 同起**（彼此无依赖）
- **T2 在 T0+T1 完成后**
- **T3 在 T1 完成后**（与 T2 并行）
- **T4 在 T2+T3 完成后**
- **T5 / T6 在 T0-T4 全完成后**（可并行跑测）
- **T7 在 T5 完成后**
- **T8 是 release 前最后一道关**

## Definition of Done

- ✅ 所有 8 任务的 unit + IT 测试 RED → GREEN
- ✅ `./mvnw -pl mbw-account verify` 全绿
- ✅ `./mvnw -pl mbw-app -Dtest=ModuleStructureTest test` 全绿（ArchUnit）
- ✅ Spotless + Checkstyle 全绿
- ✅ OpenAPI spec 含新 endpoint
- ✅ Cross-use-case enumeration defense 扩展测试 GREEN（含 9 对比组合）
- ✅ Timing defense 3 场景 P95 时延差 ≤ 50ms

## Phasing PR 拆分

- **PR 1（本 PR）**: `docs(account): login-by-password spec + plan + tasks`
- **PR 2**: `feat(account): impl login-by-password`（T0-T8 全部代码 + 测试）
