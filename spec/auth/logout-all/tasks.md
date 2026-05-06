# Implementation Tasks: Logout All Sessions

**Use case**: `logout-all`
**Spec**: [`./spec.md`](./spec.md)
**Plan**: [`./plan.md`](./plan.md)
**Phase**: M1.2 Phase 1.4（auth 会话生命周期 — bulk revoke all active refresh tokens for an account）
**Status**: ✅ Implemented（PR [#101](https://github.com/xiaocaishen-michael/my-beloved-server/pull/101) squash-merged at commit `1b33f7e`，与 refresh-token 一并合入）
**Estimated total**: ~3-4h（Phase 1 中**最简单**的 use case；无新 schema + 无新 domain + 无 retrofit；仅 application + web 层 + 测试）

## 实施记录

T0-T7 全部已交付，与 refresh-token（Phase 1.3）合并由 PR [#101](https://github.com/xiaocaishen-michael/my-beloved-server/pull/101) 一次性 squash-merge 进 main（merge commit `1b33f7e`）。

**与原 plan/tasks 的偏离**：

- 下方 "Phasing PR 拆分" 段落写"PR 1 = docs / PR 2 = logout-all impl 单独 ship"——实际为节省一次 review 周期，logout-all 与 refresh-token 共用 PR #101 同合并。
- T3 [Security] 段写 "Spring Security 配置 — `/logout-all` 加 .authenticated()"。实施时 M1.2 还**未**引入 `spring-boot-starter-security` filter chain，鉴权改在 controller 层手动 verify Bearer JWT（新增 `TokenIssuer.verifyAccess` + `JwtTokenIssuer` MACVerifier 路径），等 M3+ filter chain 落地后再上提。详见 PR #101 描述。

实施过程的完整变更明细见 PR #101 描述与 merge commit `1b33f7e`（含原 sub-commit 的 commit message 全文）。

下文段落保留原 TDD 节奏 / 任务依赖 / 测试矩阵设计意图作为 reference（同模式 use case 可参考）。

> **TDD 节奏**：每个 task 内严格红绿循环。任务标签：`[App]` / `[Web]` / `[Security]` / `[E2E]` / `[Concurrency]` / `[Contract]`。

## Critical Path（按依赖顺序）

### T0 [App] LogoutAllSessionsCommand + LogoutAllSessionsResult

**Files**:

- `mbw-account/src/main/java/com/mbw/account/application/command/LogoutAllSessionsCommand.java`
- `mbw-account/src/main/java/com/mbw/account/application/result/LogoutAllSessionsResult.java`

**Logic**:

- `LogoutAllSessionsCommand`: `record(AccountId accountId, String clientIp)`
- `LogoutAllSessionsResult`: `record(int revokedCount)` — 仅 logging 用，不返客户端

**Test**: 纯 record，无逻辑；TDD 例外。

**Dependencies**: 无。可与 T1 并行。

---

### T1 [App] LogoutAllSessionsUseCase

**File**: `mbw-account/src/main/java/com/mbw/account/application/usecase/LogoutAllSessionsUseCase.java`

**Logic**: per `plan.md` § UseCase 段，2 步执行 + `@Transactional(rollbackFor = Throwable.class)`：

1. 限流（`logout-all:<account_id>` + `logout-all:<ip>`）
2. 调 `refreshTokenRepository.revokeAllForAccount(accountId, now)` + log 影响行数

**Test**: `LogoutAllSessionsUseCaseTest`（**新建**），Mockito mock 2 依赖（RateLimitService / RefreshTokenRepository）。

**4 分支覆盖（红绿循环逐条）**：

- `should_revoke_all_and_return_count_when_account_has_active_tokens()` — happy path（mock repo 返 affected count 3 → result.revokedCount == 3）
- `should_succeed_with_zero_count_when_account_has_no_active_tokens()` — 幂等（mock repo 返 0 → result.revokedCount == 0，仍返成功）
- `should_throw_RateLimitedException_when_account_throttled()` — 账号维度限流
- `should_throw_RateLimitedException_when_ip_throttled()` — IP 维度限流
- `should_rollback_when_repo_revokeAllForAccount_fails()` — DB 异常 → 事务回滚（虽然只 1 个 DB 操作，回滚等价 noop，但保留 @Transactional 测试）

**Dependencies**: T0 完成 + 1.3 `RefreshTokenRepository.revokeAllForAccount` 已实现（编译依赖）。

---

### T2 [Web] AuthController.logoutAll endpoint

**File**: `mbw-account/src/main/java/com/mbw/account/web/controller/AuthController.java`（**1.1 已建**，扩展 method）

**Logic**: per `plan.md` § Web Layer：

- `@PostMapping("/logout-all")` + 无 request body
- 入参：`@AuthenticationPrincipal AccountId accountId` + `HttpServletRequest httpRequest`
- 调 UseCase + 返 `ResponseEntity.noContent().build()`（HTTP 204）
- Springdoc 注解齐（含 OpenAPI 描述 + 错误码 examples + `@SecurityRequirement(name = "bearerAuth")`）

**Test**: `AuthControllerLogoutAllIT`（**新建**），`@WebMvcTest(AuthController.class)` + Spring Security test slice：

- `should_return_204_when_authenticated_request_succeeds()` — happy path（mock UseCase + 模拟带 access token 的认证态）
- `should_return_401_when_no_authorization_header()` — Spring Security 拦截
- `should_return_401_when_access_token_signature_invalid()` — Spring Security 拦截
- `should_return_401_when_access_token_expired()` — Spring Security 拦截
- `should_return_401_when_authorization_format_malformed()` — Spring Security 拦截（非 Bearer 形态）
- `should_return_429_when_RateLimitedException_thrown()` — UseCase 限流
- `should_return_500_when_unexpected_exception()` — 兜底

**Dependencies**: T1 完成。

---

### T3 [Security] Spring Security 配置 — `/logout-all` 加 .authenticated()

**File**: `mbw-account/src/main/java/com/mbw/account/infrastructure/config/SecurityConfig.java`（既有，扩展）

**Logic**:

- 在 `authorizeHttpRequests` 配置中加 `.requestMatchers("/api/v1/auth/logout-all").authenticated()`
- 顺序：`/logout-all` 必须在 `/api/v1/auth/**` `.permitAll()` 之前 matcher（Spring Security matcher 顺序敏感）

**Test**: T2 内 `should_return_401_when_no_authorization_header()` 等 4 条已覆盖；本任务无独立测试类，依赖 T2 测试通过即可证 SecurityConfig 配置正确。

**Dependencies**: 无（与 T1/T2 并行）。但实际生效需 T2 同 PR 内联动。

---

### T4 [E2E] LogoutAllE2EIT

**File**: `mbw-account/src/test/java/com/mbw/account/web/LogoutAllE2EIT.java`（**新建**）

**Logic**: Testcontainers PG + Redis。覆盖 spec.md 9 个 Acceptance Scenarios + Edge Cases + SC-002 / SC-004 / SC-005。

**Test cases**：

- US1 (4 个 AS) + US2 (4 个 AS) + US3 (2 个 AS) = 10 个独立 scenarios
- US1 重点 AS：单设备退出 / 多设备退出 / access token 残留 15min 内仍可访问业务（实证 CL-001 设计 trade-off）/ 401 拦截器透明跳登录
- US2 重点 AS：4 个鉴权失败场景（无 token / 签名错 / 过期 / 格式错）→ INVALID_CREDENTIALS 字节级一致
- US3 重点 AS：账号维度限流 / IP 维度限流
- Edge：账号无 active token / FROZEN 状态仍允许 / 账号被删除（access token 还有效）
- SC-002: 50 个不同账号并发 logout-all，0 错 + 各账号下 active token 全 revoked
- SC-004: FR-006 两条限流规则验证
- SC-005: 注册 1 账号 + 3 设备登录 → logout-all → 3 个 refresh-token 调用全 401

**Fixture**: BeforeEach 起空 PG schema + 预注册账号 + 预签 N 个 refresh token (含 RefreshTokenRecord 写入，依赖 1.3 retrofit 完成)。

**Dependencies**: T0-T3 全部完成。

---

### T5 [Concurrency] LogoutAllConcurrencyIT

**File**: `mbw-account/src/test/java/com/mbw/account/web/LogoutAllConcurrencyIT.java`（**新建**）

**Logic**: per spec.md SC-003，同账号 10 并发 logout-all → 全部返 204（幂等性）+ DB UPDATE 操作收敛。

**Test cases**：

- `should_be_idempotent_when_same_account_logout_all_10_times_concurrently()` — 10 线程同时调，断言：
  - 全 10 个返 204
  - DB 内首次 UPDATE 影响 N 行（N = active token 数），后续 UPDATE 影响 0 行（partial index + WHERE revoked_at IS NULL 防重复 UPDATE）
  - 总 affected rows count == N（不会出现 N * 10）
- `should_handle_two_devices_racing_for_logout_all_same_account()` — 2 线程模拟两设备同时点退出 → 同上

**实现注**：用 `CountDownLatch` 同步起跑。DB UPDATE WHERE revoked_at IS NULL 行级锁保证：第 1 个 UPDATE 加锁 → revoke N 行；其余 9 个 UPDATE 等锁 → 锁释放后看到 revoked_at != NULL → WHERE 条件不满足 → 影响 0 行。

**Dependencies**: T0-T3 完成。可与 T4 并行。

---

### T6 [E2E] CrossUseCaseEnumerationDefenseIT 扩展

**File**: `mbw-account/src/test/java/com/mbw/account/web/CrossUseCaseEnumerationDefenseIT.java`（1.1 T13 + 1.3 T12 已扩展，**继续扩展**）

**Logic**: 加 logout-all 鉴权失败场景的 INVALID_CREDENTIALS 断言，与 register / 1.1 / 1.2 / 1.3 既有 INVALID_CREDENTIALS 响应**字节级一致**。

**Test cases（新增）**：

- `should_have_byte_identical_response_logout_all_unauthenticated_vs_register_already_vs_login_invalid_vs_refresh_invalid()` — 跨 5 个 use case 失败响应字节级一致

**Dependencies**: T4 完成。1.1 T13 / 1.3 T12 已建测试类作为基础。

---

### T7 [Contract] OpenAPI spec 验证

**File**: 无新文件；本任务是验证 Springdoc 自动生成的 OpenAPI spec 含新 endpoint。

**Logic**:

- 起本地 Spring Boot：`./mvnw spring-boot:run -pl mbw-app`
- `curl http://localhost:8080/v3/api-docs > /tmp/spec.json`
- 断言：
  - `/api/v1/auth/logout-all` 路径存在
  - method = POST + 无 requestBody
  - response 204 No Content（无 schema）
  - security: `[{ "bearerAuth": [] }]`（标识需鉴权）
  - 401 错误响应描述含 INVALID_CREDENTIALS

**Test**: 手动验证 + 可选 `OpenApiSpecIT` 扩展。

**Dependencies**: T2 完成。

---

## Parallel Opportunities

- **T0 / T1 同起**（T1 实际依赖 T0 record 类型，但可同 PR 内 staged commit）
- **T2 / T3 在 T1 完成后并行**（T3 是 SecurityConfig 改动，独立于 controller method）
- **T4 / T5 在 T0-T3 全完成后**（可并行跑测）
- **T6 在 T4 完成后**
- **T7 在 T2 完成后**（可与 T3-T6 并行）

## Definition of Done

- ✅ 所有 7 任务的 unit + IT 测试 RED → GREEN
- ✅ `./mvnw -pl mbw-account verify` 全绿（含 LogoutAllE2EIT / LogoutAllConcurrencyIT / 扩展的 CrossUseCaseEnumerationDefenseIT）
- ✅ `./mvnw -pl mbw-app -Dtest=ModuleStructureTest test` 全绿（ArchUnit）
- ✅ Spotless + Checkstyle 全绿（CI required check）
- ✅ OpenAPI spec 含 `/api/v1/auth/logout-all` + bearerAuth 标记
- ✅ Cross-use-case enumeration defense 测试 GREEN（含 logout-all 鉴权失败场景）
- ✅ Concurrency 幂等测试 GREEN（10 并发同账号全 204 + UPDATE 收敛）
- ✅ Multi-device 验证（3 设备登录 + logout-all + 3 个 refresh 全 401）

## Phasing PR 拆分

按 [meta plan file 拆 PR 顺序](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/plans/sdd-github-spec-kit-https-github-com-gi-drifting-rossum.md) Phase 1 server 部分：

- **PR 1（本 PR）**: `docs(auth): logout-all spec + plan + tasks + analysis`（仅文档）
- **PR 2**: `feat(auth): impl logout-all`（T0-T7 全部代码 + 测试）

PR 2 内部按依赖关系迭代提交（不强制 7 个 sub-PR），但 commit 历史应清晰展示 TDD 红绿循环。

**前置依赖**：1.3 impl PR 必须先合并（提供 `RefreshTokenRepository.revokeAllForAccount` 方法 + `account.refresh_token` schema）。
