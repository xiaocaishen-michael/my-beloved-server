# Implementation Plan: Unified Phone-SMS Auth

**Spec**: [`./spec.md`](./spec.md)
**Module**: `mbw-account`
**Phase**: M1.2 unified auth refactor（per [ADR-0016](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md)）
**Status**: Draft（pending impl）

## 架构层级与职责（DDD 五层）

```text
web        AccountAuthController#phoneSmsAuth
              ↓ PhoneSmsAuthRequest (DTO)
application UnifiedPhoneSmsAuthUseCase
              ↓ (依赖注入)
domain      AccountRepository.findByPhone()
            AccountFactory.createWithPhone()
            AccountStateMachine（状态校验）
            TimingDefenseExecutor（dummy hash）
            TokenIssuer（access / refresh 签）
            SmsCodeService.verify()
              ↑
infrastructure  AccountRepositoryImpl + AccountJpaRepository
                RedisSmsCodeStore
                RateLimitService（Redis backend per ADR-0011）
                MockSmsCodeSender / 真 AliyunSmsClient
                JwtTokenIssuer（Nimbus JOSE）
```

## 核心 use case 流程

`UnifiedPhoneSmsAuthUseCase.execute(PhoneSmsAuthCommand cmd)` 单事务边界（`@Transactional(rollbackFor = Throwable.class, isolation = SERIALIZABLE)`）：

```text
1. PhonePolicy.validate(cmd.phone)
     失败 → throw InvalidPhoneFormatException → 400 INVALID_PHONE_FORMAT

2. RateLimitService.check("auth:" + cmd.phone, 24h, 5)
     超限 → throw RateLimitExceededException → 429 RATE_LIMITED + Retry-After

3. SmsCodeService.verify(cmd.phone, cmd.code)
     失败 → TimingDefenseExecutor.executeDummyHash() → throw InvalidCredentialsException → 401 INVALID_CREDENTIALS

4. accountOpt = AccountRepository.findByPhone(cmd.phone)

5. switch (accountOpt) {
     case empty:
        // 自动注册路径
        Account newAccount = AccountFactory.createWithPhone(cmd.phone)
                                .withStatus(ACTIVE)
                                .withLastLoginAt(now())
        AccountRepository.save(newAccount)
        EventPublisher.publish(new AccountCreatedEvent(newAccount.id, cmd.phone, now()))
                        // outbox 由 Spring Modulith Event Publication Registry 持久化
        token = TokenIssuer.issue(newAccount.id)
        return new PhoneSmsAuthResult(newAccount.id, token.access, token.refresh)

     case present(account) when account.status == ACTIVE:
        account.updateLastLoginAt(now())
        AccountRepository.save(account)
        token = TokenIssuer.issue(account.id)
        return new PhoneSmsAuthResult(account.id, token.access, token.refresh)

     case present(account) when account.status in [FROZEN, ANONYMIZED]:
        // 反枚举吞下
        TimingDefenseExecutor.executeDummyHash()
        throw InvalidCredentialsException → 401 INVALID_CREDENTIALS
   }

6. RateLimitService.recordFailure("auth:" + cmd.phone)
     // 仅当步骤 3 / 5 抛 InvalidCredentialsException 时执行（异常处理流程在 Controller 层）
```

## 数据流（请求生命周期）

```text
HTTP POST /api/v1/accounts/phone-sms-auth
{phone, code}
   ↓
AccountAuthController#phoneSmsAuth(@Valid @RequestBody PhoneSmsAuthRequest req)
   ↓ (MapStruct: PhoneSmsAuthRequest → PhoneSmsAuthCommand)
UnifiedPhoneSmsAuthUseCase.execute(cmd) [事务边界]
   ↓ (返回 PhoneSmsAuthResult)
AccountAuthController 转 PhoneSmsAuthResponse
   ↓ JSON serialize
HTTP 200 {accountId, accessToken, refreshToken}
```

错误路径：domain exception → `mbw-shared.web.GlobalExceptionHandler` → RFC 9457 ProblemDetail（`application/problem+json`）。

## SMS code 路径（独立 endpoint）

`AccountSmsCodeController#requestSmsCode` (POST `/api/v1/accounts/sms-codes`) 也需重构（per spec FR-004）：

```text
POST /api/v1/accounts/sms-codes
{phone}      // 删除 purpose 字段（per ADR-0016 决策 1，server 内部决定 template）

UseCase: RequestSmsCodeUseCase（既有，改逻辑）
1. PhonePolicy.validate(phone)
2. RateLimitService.check("sms:" + phone, 60s, 1)
3. RateLimitService.check("sms:" + phone, 24h, 10)
4. RateLimitService.check("sms:" + ip, 24h, 50)
5. code = SmsCodeService.generate()  // 6位数字
6. RedisSmsCodeStore.put(phone, sha256(code), TTL=5min)
7. SmsCodeSender.send(phone, code)  // Template A 统一（删 Template C）
8. return 200
```

无论 phone 是否存在 / 何状态，路径完全一致 — 反枚举要求字节级 + 时延一致。

## 复用既有基础设施（不新增）

- `RateLimitService`（既有，Redis backend per ADR-0011）— 新增 `auth:<phone>` bucket
- `RedisSmsCodeStore`（既有）
- `MockSmsCodeSender`（既有 mock 通道，per ADR-0013 amendments）
- `TimingDefenseExecutor`（既有，复用为 unified auth dummy hash）
- `TokenIssuer`（既有）
- `AccountRepository` interface + `AccountRepositoryImpl`（方式 A 纯接口 + JPA 适配，per ADR-0008）
- `AccountStateMachine`（既有）
- `AccountCreatedEvent`（既有事件类，schema 不变）
- `mbw-shared.web.GlobalExceptionHandler` + RFC 9457 ProblemDetail
- Spring Modulith Event Publication Registry（outbox）

## 新建组件

| 类 | 层 | 职责 |
|---|---|---|
| `UnifiedPhoneSmsAuthUseCase` | application | 主流程编排（per § 核心 use case 流程）|
| `PhoneSmsAuthCommand` | application | UseCase 入参 record `(phone, code)` |
| `PhoneSmsAuthResult` | application | UseCase 返回值 record `(accountId, accessToken, refreshToken)` |
| `AccountAuthController` | web | endpoint 路由 + DTO 转 Command + 响应映射 |
| `PhoneSmsAuthRequest` | web | HTTP 请求体 `(phone, code)` + Jakarta Validation 注解 |
| `PhoneSmsAuthResponse` | web | HTTP 响应体（与 既有 `LoginResponse` 同形态，可考虑直接复用 `LoginResponse` 类 + 重命名注释）|
| `SingleEndpointEnumerationDefenseIT` | test | 1000 次请求验 SC-003 字节级 / 时延差 ≤ 50ms |
| `UnifiedPhoneSmsAuthE2EIT` | test | 4 场景：已注册 / 未注册 / FROZEN / 码错 |

## 删除组件（per spec FR-012 一刀切）

| 类 | 层 |
|---|---|
| `RegisterByPhoneUseCase` / `Command` / `Result` / `Request` / `Response` | application + web |
| `AccountRegisterController#registerByPhone`（method）；controller 自身保留作 `/sms-codes` 入口并于本 PR cycle 重命名为 `AccountSmsCodeController`（per ADR-0016 命名一致性） | web |
| `LoginByPhoneSmsUseCase` / `Command` / `Result` / `Request` / `Response` | application + web |
| `LoginByPasswordUseCase` / `Command` / `Result` / `Request` / `Response` | application + web |
| `AuthController#loginByPhoneSms` + `#loginByPassword`（method） | web |
| `RegisterByPhoneE2EIT` / `LoginByPhoneSmsE2EIT` / `LoginByPasswordE2EIT` / `CrossUseCaseEnumerationDefenseIT` | test |
| Template C 配置 `SMS_TEMPLATE_LOGIN_UNREGISTERED` | infrastructure |

新 `AccountAuthController` 取代旧 `AuthController` + 旧 `AccountRegisterController` 中的 auth 相关 method；`requestSmsCode` 由独立 `AccountSmsCodeController`（旧名 `AccountRegisterController`，本 PR cycle 已重命名）承担。

## 数据模型变更

无 schema 变更：

- `Account.phone` / `Account.status` / `Account.last_login_at` 字段不变
- `Account.email` 字段保留 schema（`[DEPRECATED M1.2 ADR-0016]`，per PRD 修订），impl 不写新值
- `Account.password_hash` 字段保留 schema（`[DEPRECATED M1.2 ADR-0016]`），仅作 `TimingDefenseExecutor` 的 dummy hash 计算输入（per CL-003）

无 Flyway migration 必需。

## 反枚举设计（核心安全约束）

对照 SC-003 4 种响应分支：

| 分支 | HTTP | Body | 时延控制 |
|---|---|---|---|
| 已注册 ACTIVE 成功 | 200 | `{accountId, accessToken, refreshToken}` | 自然耗时（DB read + token sign）|
| 未注册自动注册成功 | 200 | `{accountId, accessToken, refreshToken}` 同形态 | 自然耗时（DB read + write + token sign）— 通过事务隔离 + outbox 保证一致性 |
| FROZEN / ANONYMIZED 失败 | 401 | `{code: "INVALID_CREDENTIALS", ...}` ProblemDetail | TimingDefenseExecutor dummy hash |
| 码错 / 码过期失败 | 401 | 同上 | 同上（TimingDefenseExecutor 同入口拦截）|

**关键约束**：成功路径 vs 失败路径自然耗时差距由 dummy hash 计算补齐（5-15ms BCrypt cost=12）；E2E 测试 1000 次请求 P95 时延差 ≤ 50ms。

## 事件流（outbox + Spring Modulith）

| 路径 | Event | Publish 时机 |
|---|---|---|
| 已注册 ACTIVE login | （无）— 与既有 login-by-phone-sms 一致，不发事件 | — |
| 未注册自动注册 | `AccountCreatedEvent`（既存类）| use case 事务内，commit 时 outbox 持久化 |
| FROZEN / ANONYMIZED / 码错 | （无）| — |

跨模块订阅者（M2+ 计划）：

- `mbw-pkm` 监听 `AccountCreatedEvent` 初始化默认 PKM workspace
- `mbw-billing`（M1.3+）监听 `AccountCreatedEvent` 初始化默认 Plan 配额

本 use case 不引入新事件，仅复用 `AccountCreatedEvent`。

## 测试策略

| 层 | 测试类 | 覆盖 |
|---|---|---|
| Domain unit | `AccountFactoryTest` / `AccountStateMachineTest` | 已存在，无需改 |
| Application unit | `UnifiedPhoneSmsAuthUseCaseTest`（新）| 4 分支 + 限流 + 异常处理；mock repo / sms / token / timing |
| Integration | `UnifiedPhoneSmsAuthE2EIT`（新）| User Stories 1-4 全场景，Testcontainers PG + Redis + Mock SMS |
| Integration | `SingleEndpointEnumerationDefenseIT`（新，替代旧 `CrossUseCaseEnumerationDefenseIT`）| SC-003 1000 次请求 P95 时延差 ≤ 50ms |
| ArchUnit / Spring Modulith | `ModuleStructureTest`（既有）| 仍 0 violation；新 use case 遵守 5 层规则 |
| Smoke (web) | 由前端 B2 真后端冒烟覆盖（per `docs/experience/claude-design-handoff.md` § 6）| 4 状态 Playwright 截图 |

TDD 节奏（per server CLAUDE.md § 一）：先写 use case unit test 红 → 实现 → 绿 → IT；最后写 SingleEndpointEnumerationDefenseIT 验时延。

## API 契约变更（OpenAPI + 前端 client）

| 维度 | 变化 |
|---|---|
| `/api/v1/accounts/sms-codes` | 入参 `purpose` 字段删除；server 内部决定 template |
| `/api/v1/accounts/phone-sms-auth` | **新增**；入参 `{phone, code}`；响应 `LoginResponse` 复用 |
| `/api/v1/accounts/register-by-phone` | **删除** |
| `/api/v1/auth/login-by-phone-sms` | **删除** |
| `/api/v1/auth/login-by-password` | **删除** |
| OpenAPI tag | "Account Auth" + "Account Sms Code" 双 tag 取代旧 "Account Register" + "Auth"（信息架构简化；register UX 概念消失） |

前端 `pnpm api:gen` 拉新 spec 自动同步：删 `AuthControllerApi`，`AccountRegisterControllerApi` 重命名为 `AccountSmsCodeControllerApi`，新增 `AccountAuthControllerApi`。

## Constitution Check

- ✅ Modular Monolith：无跨模块 import；mbw-account 自包含
- ✅ DDD 5 层：use case 严守 application 层职责，不感知 HTTP / DB 细节
- ✅ Repository 方式 A：domain 纯接口 `AccountRepository`，JPA 实现在 infrastructure
- ✅ TDD：所有新代码先写测试（per server CLAUDE.md）
- ✅ Spring Modulith Verifier：跨模块只经过 `api` 包；本 use case 不需要跨模块
- ✅ ArchUnit：domain 零 framework 依赖；application 仅依赖 domain
- ✅ expand-migrate-contract 不适用（无 schema 变更）

## 反模式（明确避免）

- ❌ 在 client 前端做 phone "已注册预查"接口（违反单接口反枚举设计哲学，per ADR-0016 Alternatives Considered）
- ❌ 让客户端传 `purpose` 字段决定模板（违反 server 自主分支原则，per spec FR-004）
- ❌ 在 use case 内调 Spring `RestTemplate` / `WebClient` / 任何 IO（必须经 domain 接口）
- ❌ 复用 `RegisterByPhoneUseCase` / `LoginByPhoneSmsUseCase` 内部代码（一刀切删，避免 dead code）
- ❌ 给 `password_hash` 字段写新值（per FR-005 / FR-006，仅作 dummy hash 输入）

## References

- [`./spec.md`](./spec.md)
- [`./tasks.md`](./tasks.md)
- [ADR-0001](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0001-modular-monolith.md) Modular Monolith
- [ADR-0008](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0008-repository-pure-interface.md) Repository 方式 A
- [ADR-0011](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0011-rate-limit-jcache-then-redis.md) RateLimit
- [ADR-0013](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0013-defer-sms-to-business-license.md) SMS mock
- [ADR-0016](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md) 上游决策
- [`specs/account/account-state-machine.md`](../account-state-machine.md)（同 PR 加 "Auto-create on phone-sms-auth" 段）
- 历史 spec：`specs/account/{register-by-phone,login-by-phone-sms,login-by-password}/`（各加 SUPERSEDED.md）
