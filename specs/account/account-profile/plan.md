# Implementation Plan: Account Profile

**Spec**: [`./spec.md`](./spec.md)
**Module**: `mbw-account`
**Phase**: M1.2 onboarding 信号 + displayName 维护（per [ADR-0016](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md) + [ADR-0017](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0017-sdd-business-flow-first-then-mockup.md)）
**Status**: Draft（pending impl）

## 架构层级与职责（DDD 五层）

```text
web        AccountProfileController#getMe / patchMe
              ↓ DisplayNameUpdateRequest (DTO)
application AccountProfileApplicationService.getMe / updateDisplayName
              ↓ (依赖注入)
domain      AccountRepository.findById()
            DisplayName（值对象，新建）
            AccountStateMachine.changeDisplayName（新增 facade）
              ↑
infrastructure  AccountRepositoryImpl + AccountJpaRepository (扩展 mapping)
                JwtAuthFilter（新建 Spring OncePerRequestFilter）
                SecurityConfig（新建 / 改造）
                JwtTokenIssuer.verifyAccess（既有）
                Flyway V6__add_account_display_name.sql
```

## 核心 use case 流程

### GET `/api/v1/accounts/me`

`AccountProfileApplicationService.getMe(accountId)` — 只读，无事务边界（默认 `@Transactional(readOnly = true)`）：

```text
1. Filter 已验签 access token，注入 AccountId 到 SecurityContext / RequestAttribute
2. controller 取 accountId → applicationService.getMe(accountId)
3. AccountRepository.findById(accountId)
     not found → throw AccountNotFoundException → 401（FR-002 反枚举吞，不暴露 404）
     status != ACTIVE → throw AccountInactiveException → 401（FR-009）
     present + ACTIVE → 继续
4. RateLimitService.check("me-get:" + accountId, 60s, 60)
     超限 → throw RateLimitExceededException → 429
5. Map Account → AccountProfileResult (record)
6. Controller 转 AccountProfileResponse → 200 JSON
```

### PATCH `/api/v1/accounts/me`

`AccountProfileApplicationService.updateDisplayName(accountId, rawDisplayName)` — 单事务边界（`@Transactional(rollbackFor = Throwable.class)`）：

```text
1. Filter 验签同 GET 路径
2. controller 取 accountId + body.displayName → applicationService.updateDisplayName(accountId, rawDisplayName)
3. RateLimitService.check("me-patch:" + accountId, 60s, 10)
     超限 → throw RateLimitExceededException → 429

4. DisplayName displayName = new DisplayName(rawDisplayName)  // 构造校验 FR-005
     失败 → IllegalArgumentException("INVALID_DISPLAY_NAME: ...") → 400

5. account = AccountRepository.findById(accountId)
     not found → 401 反枚举吞
     status != ACTIVE → 401 反枚举吞

6. AccountStateMachine.changeDisplayName(account, displayName, now())
     // package-private Account#setDisplayName 由此 facade 调用
     // status 检查 + 写 displayName + 更新 updatedAt

7. AccountRepository.save(account)

8. return new AccountProfileResult(account.id, account.displayName, account.status, account.createdAt)
```

幂等性：步骤 6 检测同值时仍写一次（更新 `updated_at`），但响应 200 与新值场景字节级一致 — 客户端不感知差异。

## 数据流（请求生命周期）

```text
HTTP GET /api/v1/accounts/me
Authorization: Bearer <access_token>
   ↓
JwtAuthFilter.doFilterInternal
   ↓ 验签 → set RequestAttribute "accountId" : Long
AccountProfileController#getMe(@AuthenticatedAccountId Long accountId)
   ↓
AccountProfileApplicationService.getMe(accountId)
   ↓ (返回 AccountProfileResult)
AccountProfileController 转 AccountProfileResponse
   ↓ JSON serialize
HTTP 200 {accountId, displayName, status, createdAt}
```

PATCH 路径同上 + body `{displayName}`，响应同 GET 200 形态。

错误路径：domain / application exception → `mbw-shared.web.GlobalExceptionHandler` → RFC 9457 ProblemDetail（`application/problem+json`）。

## JWT 前置基础设施（本 spec 引入）

**背景**：当前 server 端无任何 endpoint 鉴权（M1.1 / M1.2 phoneSmsAuth / sms-codes 全 anonymous）。`/me` 是首个需 Bearer JWT 的接口，本 spec 必须引入 Spring Security filter chain。

### 组件

| 组件 | 层 | 职责 |
|---|---|---|
| `JwtAuthFilter` | infrastructure.security | `OncePerRequestFilter` 子类；从 `Authorization: Bearer <jwt>` 取 token → 调 `JwtTokenIssuer.verifyAccess(jwt)` 验签 → 解出 sub claim 即 accountId → 写 `request.setAttribute("accountId", accountId)` + `SecurityContextHolder` 简化 Authentication；失败不抛，让请求进 controller，由 controller 通过 `@AuthenticatedAccountId` 缺值时返 401 |
| `SecurityConfig` | infrastructure.security | `@EnableWebSecurity`；`SecurityFilterChain` Bean；filter 注册到 `/api/v1/accounts/me` / `/api/v1/accounts/me/**` 路径前；其他路径（phoneSmsAuth / sms-codes / actuator）保持 anonymous |
| `@AuthenticatedAccountId` | web.security | `@Parameter` annotation + `HandlerMethodArgumentResolver`，从 request attribute 取 accountId 注入 controller 方法参数；缺值 → throw `MissingAuthenticationException` → 401 |
| `MissingAuthenticationException` | web.exception | extends RuntimeException；mapped 到 401 ProblemDetail by `GlobalExceptionHandler` |

### 反枚举一致路径

GET / PATCH `/me` 4 种"非 200"响应（无 token / 过期 token / 无效签名 / 账号 status != ACTIVE）必须返**字节级一致**的 401 ProblemDetail：

| 场景 | 触发点 | HTTP | Body |
|---|---|---|---|
| 无 `Authorization` 头 | JwtAuthFilter 不写 attribute → controller `@AuthenticatedAccountId` 缺值 → MissingAuthenticationException | 401 | `{"type":".../auth-failed","title":"Authentication failed","status":401,"code":"AUTH_FAILED"}` |
| Bearer token 签名无效 / 过期 | JwtAuthFilter 验签 throw → 不写 attribute → 同上 | 401 | 同上 |
| token 合法但账号 not found | applicationService 抛 AccountNotFoundException | 401 | 同上 |
| token 合法但账号 status != ACTIVE | applicationService 抛 AccountInactiveException | 401 | 同上 |

由 `JwtAuthFailureUniformnessIT` 集成测试 grep 4 路径 body 字节级一致。

## 复用既有基础设施（不新增）

- `RateLimitService`（既有，Redis backend per ADR-0011）— 新增 `me-get:<accountId>` + `me-patch:<accountId>` bucket
- `AccountRepository` interface + `AccountRepositoryImpl`（方式 A 纯接口 + JPA 适配，per ADR-0008）— 扩展 `findById` 已有；本 spec 复用
- `AccountStateMachine`（既有）— 新增 `changeDisplayName(Account, DisplayName, Instant)` facade method
- `JwtTokenIssuer`（既有）— 复用 `verifyAccess(String jwt) → AccountId` 方法
- `mbw-shared.web.GlobalExceptionHandler` + RFC 9457 ProblemDetail 映射
- Spring Modulith Event Publication Registry（outbox）— 本 spec 不发事件，但保留通道作 M2+ 扩展（per FR-011）

## 新建组件

| 类 | 层 | 职责 |
|---|---|---|
| `DisplayName` | domain.model | record-style VO mirror `PhoneNumber.java`；构造校验 FR-005 |
| `AccountStateMachine#changeDisplayName` | domain.service | 新 facade method；status 验证后调 `Account#setDisplayName(DisplayName, Instant)`（package-private） |
| `Account#setDisplayName` | domain.model | package-private mutator；写 displayName + 更新 updatedAt |
| `AccountProfileApplicationService` | application.service | 编排 GET / PATCH；事务边界 |
| `AccountProfileResult` | application | record `(accountId, displayName, status, createdAt)` |
| `AccountProfileController` | web.controller | `@RestController`；`@GetMapping("/me")` + `@PatchMapping("/me")` |
| `AccountProfileResponse` | web | HTTP 响应体；nullable displayName |
| `DisplayNameUpdateRequest` | web | HTTP 请求体；`@NotBlank` + 长度 raw bound（精校验由 VO 兜底） |
| `JwtAuthFilter` | infrastructure.security | OncePerRequestFilter；解析 Bearer + 写 accountId 到 request |
| `SecurityConfig` | infrastructure.security | `@EnableWebSecurity` + filter chain 配置 |
| `@AuthenticatedAccountId` + `AuthenticatedAccountIdResolver` | web.security | controller 参数注入 + 缺值 401 |
| `MissingAuthenticationException` | web.exception | 401 触发器 |
| `AccountNotFoundException` | application.exception | 401 反枚举吞触发器（disambiguates 与 displayName 校验 400） |
| `AccountInactiveException` | application.exception | 401 反枚举吞触发器（FR-009） |
| `Flyway V6__add_account_display_name.sql` | infrastructure.persistence | `ALTER TABLE account.account ADD COLUMN display_name VARCHAR(64)` |
| `AccountProfileApplicationServiceTest` | test (application unit) | mock repo / state machine；7 场景 |
| `AccountProfileControllerTest` | test (web slice) | `@WebMvcTest`；4 状态码映射 |
| `DisplayNameTest` | test (domain unit) | 构造校验 8 个 case（per SC-006） |
| `AccountProfileE2EIT` | test (integration) | Testcontainers PG + Redis；User Stories 1-4 全场景 |
| `JwtAuthFailureUniformnessIT` | test (integration) | 4 路径 401 字节级一致（per § 反枚举一致路径） |

## 删除组件

无（本 spec 仅扩展，不删旧 endpoint / use case）。

## 数据模型变更

### V6 migration

```sql
-- mbw-account/src/main/resources/db/migration/account/V6__add_account_display_name.sql
ALTER TABLE account.account
    ADD COLUMN display_name VARCHAR(64);

-- 无 default value（默认 NULL）
-- 无 unique index（per FR-006 / CL-002）
-- 无 NOT NULL（per FR-007 auto-create 时为 null）
```

`VARCHAR(64)` 字节宽度上限 — Unicode 码点 32 + UTF-8 最坏 4 字节/码点 = 128 字节理论上限；实际以 BMP CJK 3 字节 + emoji 4 字节均值约 96 字节足够，但 PG VARCHAR 是字符数（PG 默认 server encoding=UTF-8）非字节数 → `VARCHAR(64)` 在码点意义上是 64 字符，对 32 码点上限有 2× 余量。

### Account JPA Entity 改

`AccountJpaEntity` 加 `@Column(name = "display_name", length = 64) String displayName`（nullable）；`AccountMapper`（MapStruct）双向 map domain `DisplayName` ↔ JPA `String`：

- domain → JPA: `displayName != null ? displayName.value() : null`
- JPA → domain: `displayName != null ? new DisplayName(displayName) : null`
  - 注意：DB 中已存的值是 trim 后合法值（V6 不引入旧数据），构造 `DisplayName(rawValue)` 应不抛；但需要在 mapper 中容忍 `IllegalArgumentException`（数据腐化兜底 → 视为 null + 记 WARN log）

### expand-migrate-contract 跳步说明

per server CLAUDE.md § 五跳步条件：

1. **无真实用户数据** ✅ M1.2 阶段 v0.x.x dev / staging
2. **PR 描述明示** "跳过 expand-migrate-contract，理由：M1.2 dev 阶段无真实用户；新增 nullable 列纯 expand 操作，无 contract 需求"

V6 是纯 expand（加 nullable 新列，无 NOT NULL 约束 / 无 backfill / 无 drop column），按定义已是 expand-migrate-contract 的第一步独立 PR 形态，不存在"跳步"问题。

## 反枚举设计（边界确认）

| 维度 | phoneSmsAuth（per ADR-0016 FR-006）| account-profile（本 spec）|
|---|---|---|
| 反枚举目标 | 不暴露 phone 是否注册 | 不暴露 accountId 是否存在 / 是否 ACTIVE |
| 适用 endpoint | `/phone-sms-auth` 4 路径字节级一致 | `/me` 4 路径 401 字节级一致（per § JWT 前置基础设施 反枚举一致路径） |
| 受 spec 影响的现有 endpoint 字节 | **零改动**（关键约束）| — |
| timing defense | 必须（dummy bcrypt 5-15ms）| 不必须（401 路径无成功路径对照，无 timing 信号需要混淆）|

**关键约束**：本 spec 不向 `phoneSmsAuth` / `sms-codes` 响应中加任何字段；displayName **仅**出现在 `/me` 响应。SC-003 grep 验证。

## 事件流（outbox + Spring Modulith）

| 路径 | Event | Publish 时机 |
|---|---|---|
| GET `/me` | （无）| — |
| PATCH `/me` 成功 | （无，per FR-011）| — |
| PATCH `/me` 失败 | （无）| — |

跨模块订阅者：当前无；M2+ 真有需求（如 mbw-pkm 同步 displayName 到 PKM workspace title）再引入 `AccountProfileUpdatedEvent`。

## 测试策略

| 层 | 测试类 | 覆盖 |
|---|---|---|
| Domain unit | `DisplayNameTest`（新）| 构造校验 SC-006 8 个 case |
| Domain unit | `AccountStateMachineTest`（既有，扩展）| `changeDisplayName(active)` 通 / `changeDisplayName(frozen)` 拒 / `changeDisplayName(anonymized)` 拒 |
| Application unit | `AccountProfileApplicationServiceTest`（新）| getMe（active / not-found / inactive / 限流）/ updateDisplayName（active / 校验失败 / 限流 / inactive 反枚举吞）共 7 场景；mock repo / state machine / RateLimitService |
| Web slice | `AccountProfileControllerTest`（新）| `@WebMvcTest`；200 / 400 / 401 / 429 4 状态码映射 |
| Integration | `AccountProfileE2EIT`（新）| User Stories 1-4 全场景，Testcontainers PG + Redis；GET / PATCH / 限流 / FROZEN-token 拒接 |
| Integration | `JwtAuthFailureUniformnessIT`（新）| 4 路径 401 body 字节级一致（含 timestamp 占位字段排除策略）|
| ArchUnit / Spring Modulith | `ModuleStructureTest`（既有）| 仍 0 violation；DisplayName 在 domain 0 framework 依赖；JwtAuthFilter 在 infrastructure |
| 反枚举边界 grep | `bytePassthroughGrepTest`（新）| 静态分析：`PhoneSmsAuthResponse.class` / `LoginResponse.class` 字段集不含 displayName |

TDD 节奏（per server CLAUDE.md § 一）：T0 DisplayName 单测红 → impl 绿 → T1 Account 聚合 → T2 V6 migration → T3 JPA / Mapper → T4 ApplicationService → T5 JwtAuthFilter（含 Security config）→ T6 Controller → T7-T8 IT。

## API 契约变更（OpenAPI + 前端 client）

| 维度 | 变化 |
|---|---|
| `GET /api/v1/accounts/me` | **新增**；响应 `AccountProfileResponse {accountId, displayName: string \| null, status, createdAt}` |
| `PATCH /api/v1/accounts/me` | **新增**；入参 `DisplayNameUpdateRequest {displayName: string}`；响应同 GET 形态 |
| `POST /api/v1/accounts/phone-sms-auth` | **零改动**（per SC-003 反枚举不变性）|
| `POST /api/v1/accounts/sms-codes` | **零改动** |
| OpenAPI tag | "Account Profile" 新增；与 "Account Auth" / "Account Sms Code" 并列 |

前端 `pnpm api:gen:dev` 拉新 spec 自动同步：新增 `AccountProfileControllerApi`，含 `getMe()` / `patchMe(DisplayNameUpdateRequest)`；既有 `getAccountAuthApi` / `getAccountSmsCodeApi` 类型不变。

## Constitution Check

- ✅ Modular Monolith：本 spec 全部新增类在 `mbw-account` 模块内；无跨模块 import
- ✅ DDD 5 层：DisplayName / AccountStateMachine.changeDisplayName 在 domain（零 framework）；ApplicationService 在 application；JwtAuthFilter / SecurityConfig 在 infrastructure；Controller / Resolver 在 web
- ✅ Repository 方式 A：复用既有 `AccountRepository` 纯接口 + Impl 扩展
- ✅ TDD：所有新代码先写测试（per server CLAUDE.md）；测试任务绑定到实现 task（per Constitution § III）
- ✅ Spring Modulith Verifier：跨模块只经过 `api` 包；本 spec 不需要跨模块
- ✅ ArchUnit：domain 零 framework 依赖（DisplayName 是 record + JDK 类型）；application 仅依赖 domain + repo + RateLimitService
- ✅ expand-migrate-contract：V6 是 pure expand（nullable 新列）— 单 PR 落合规
- ✅ OpenAPI = data, spec.md = rules：spec.md 不写 OpenAPI yaml；schema 由 Springdoc 反射推导

## 反模式（明确避免）

- ❌ 在 phoneSmsAuth 响应里加 `displayName` / `isNewAccount` 字段（破坏 ADR-0016 FR-006 字节级反枚举不变性）
- ❌ 让客户端用 GET 响应的 `displayName == null` 之外的信号判断 onboarding（如 phoneSmsAuth 响应里加 hint）— 违反"信号源唯一"
- ❌ 在 controller 内做 JWT 验签（应在 filter 层）；controller 仅取 `accountId` 注入参数
- ❌ DB 给 `display_name` 加 unique constraint（per FR-006 / CL-002）
- ❌ DisplayName VO 用 framework annotation（如 `@NotBlank`）— 应用 Jakarta Validation 在 web Request DTO，VO 自验证
- ❌ PATCH 成功路径发 outbox event（per FR-011 当前无消费方）
- ❌ 在 JwtAuthFilter 里查 DB 验 status（应留给 application 层查；filter 只验签 + 解 sub）— 保持 filter 只关心 token 真假，业务状态由 application 兜底

## References

- [`./spec.md`](./spec.md)
- [`./tasks.md`](./tasks.md)
- [ADR-0001](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0001-modular-monolith.md) Modular Monolith
- [ADR-0008](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0008-repository-pure-interface.md) Repository 方式 A
- [ADR-0011](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0011-rate-limit-jcache-then-redis.md) RateLimit
- [ADR-0016](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md) 上游 unified auth 决策
- [ADR-0017](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0017-sdd-business-flow-first-then-mockup.md) SDD 业务流先行
- [`specs/account/phone-sms-auth/`](../phone-sms-auth/) — auth 上游 use case（响应 schema 不可变约束的来源）
- 前端配套 plan：[`apps/native/specs/onboarding/plan.md`](https://github.com/xiaocaishen-michael/no-vain-years-app/blob/main/apps/native/specs/onboarding/plan.md)
