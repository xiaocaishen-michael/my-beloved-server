# Implementation Plan: Register by Phone

**Branch**: `docs/account-register-by-phone-plan`
**Date**: 2026-04-28
**Spec**: [`spec.md`](./spec.md)
**Module**: `mbw-account`

## Summary

按 spec.md 13 条 FR 把"手机号 + 短信验证码注册"落到 DDD 五层骨架。聚合根 `Account` + 值对象 `PhoneNumber`/`PasswordHash`/`VerificationCode` + Domain Service `PhonePolicy`/`PasswordPolicy`/`AccountStateMachine` + 两条 UseCase（`RequestSmsCodeUseCase` / `RegisterByPhoneUseCase`）+ JPA Adapter + Spring MVC Controller + Springdoc OpenAPI 注解。跨模块依赖 `mbw-shared.RateLimitService`（Redis backend）+ `mbw-shared.web.GlobalExceptionHandler`（已有 PR #27）。

## Technical Context

| 项 | 选型 |
|---|------|
| Language | JDK 21 |
| Framework | Spring Boot 3.5.x + Spring MVC + Spring Modulith 1.4 |
| Persistence | Spring Data JPA + Hibernate + PostgreSQL 16（schema `account`）|
| Cache / KV | Redis（VerificationCode + RateLimit bucket）|
| Validation | Jakarta Validation（`@NotNull` / `@Pattern`）|
| Crypto | Spring Security `BCryptPasswordEncoder`（cost=12）+ Nimbus JOSE JWT |
| External | 阿里云短信 SDK（aliyun-java-sdk-dysmsapi）|
| Testing | JUnit 5 + Mockito + Testcontainers（PG / Redis / MockServer for SMS） + ArchUnit |
| API Doc | Springdoc OpenAPI v3（注解 → `/v3/api-docs`） |
| Performance Goal | SC-001 P95 ≤ 800ms |
| Scale (M1.1) | 单实例，无 SLA 目标，仅本人 + 内测；M3 内测起再压测 |

## Constitution Check

按 `.specify/memory/constitution.md` 项目宪法逐条核对：

- ✅ **DDD 5-layer**：domain（值对象 / 聚合 / Domain Service / Repository 接口）、application（UseCase）、infrastructure（JPA Adapter / SmsClient）、web（Controller / Request/Response）、api（跨模块对外）— 严格分层
- ✅ **TDD**：每 task 走红绿循环（plan 不写代码，tasks 阶段定）
- ✅ **Conventional Commits**：实现期 `feat(account):`，spec/plan 期 `docs(account):`
- ✅ **ArchUnit 边界**：domain 不依赖 Spring/JPA/Jackson；跨模块只走 api 包
- ✅ **跨模块通信规则**：mbw-account → mbw-shared（API 接口），不反向；不直接 import 其他业务模块
- ✅ **DB schema 隔离**：`account.account` / `account.credential`（schema=account）
- ✅ **SDD 四步法**：本 plan 为 Step 2 产物
- ✅ **Repository 方式 A**（ADR-0008）：domain 纯接口 + infrastructure JPA 适配器 + MapStruct
- ✅ **限流框架**（ADR-0011 amended）：用 mbw-shared.RateLimitService（Redis backend）

无违反项。

## Project Structure

### Spec docs

```text
spec/account/register-by-phone/
├── spec.md            # ✅ 已有（PR #44/#45/#46 三轮 rev）
├── plan.md            # ← 本 PR
├── tasks.md           # 下个 PR (/speckit.tasks)
└── research.md        # 不需要（无技术调研未决项）
```

### Source code（按 DDD 5-layer 落到 mbw-account）

```text
mbw-account/src/main/java/com/mbw/account/
├── api/                                     # 跨模块对外
│   ├── service/                             # 暂无（本 use case 无跨模块同步调用）
│   ├── dto/AccountRegisteredEvent.java      # 跨模块事件契约
│   └── error/AccountErrorCode.java          # 模块特有错误码 enum
│
├── domain/                                  # 业务规则（零外部依赖）
│   ├── model/
│   │   ├── Account.java                     # 聚合根
│   │   ├── AccountId.java                   # 值对象
│   │   ├── AccountStatus.java               # enum {ACTIVE}
│   │   ├── PhoneNumber.java                 # E.164 值对象（CL-002）
│   │   ├── PasswordHash.java                # BCrypt 值对象（持有 hash 不持明文）
│   │   ├── VerificationCode.java            # 值对象（含 attemptCount）
│   │   └── credential/                      # Credential 类型层级
│   │       ├── Credential.java              # 接口
│   │       ├── PhoneCredential.java         # 必有
│   │       └── PasswordCredential.java      # nullable（CL-001）
│   ├── service/
│   │   ├── PhonePolicy.java                 # E.164 + 大陆段校验（FR-001）
│   │   ├── PasswordPolicy.java              # 8 字符 + 大小写 + 数字（FR-003）
│   │   └── AccountStateMachine.java         # FR-004 (无)→ACTIVE 转换
│   ├── event/
│   │   └── AccountRegistered.java           # Domain event（生产侧）
│   └── repository/                          # 纯接口（方式 A）
│       ├── AccountRepository.java
│       └── VerificationCodeRepository.java  # Redis backed
│
├── application/                             # 编排
│   ├── usecase/
│   │   ├── RequestSmsCodeUseCase.java       # FR-002/006/012
│   │   └── RegisterByPhoneUseCase.java      # FR-002~011/013
│   ├── command/
│   │   ├── RequestSmsCodeCommand.java       # phone
│   │   └── RegisterByPhoneCommand.java      # phone / code / passwordOpt
│   └── result/
│       └── RegisterByPhoneResult.java       # accountId / accessToken / refreshToken
│
├── infrastructure/                          # IO 实现
│   ├── persistence/
│   │   ├── AccountJpaEntity.java            # JPA entity（@Table schema=account）
│   │   ├── CredentialJpaEntity.java
│   │   ├── AccountJpaRepository.java        # Spring Data 接口
│   │   ├── CredentialJpaRepository.java
│   │   ├── AccountRepositoryImpl.java       # @Repository，实现 domain 接口
│   │   ├── AccountMapper.java               # MapStruct
│   │   └── RedisVerificationCodeRepository.java   # Redis backed
│   ├── messaging/
│   │   └── AccountEventPublisher.java       # Spring Modulith 事件发布
│   ├── client/
│   │   └── AliyunSmsClient.java             # 阿里云短信 SDK 适配
│   ├── crypto/
│   │   ├── BCryptPasswordHasher.java        # 包 BCryptPasswordEncoder
│   │   ├── DummyBcryptTimingDefense.java    # FR-013 入口 dummy bcrypt
│   │   └── JwtTokenIssuer.java              # Nimbus JOSE access/refresh 签发
│   └── config/
│       ├── AccountProperties.java           # @ConfigurationProperties
│       ├── JwtProperties.java               # FR-008 secret env 校验
│       └── AccountConfig.java               # Bean 装配
│
└── web/                                     # HTTP
    ├── controller/AccountRegisterController.java
    ├── request/RequestSmsCodeRequest.java
    ├── request/RegisterByPhoneRequest.java
    ├── response/RegisterByPhoneResponse.java
    └── exception/                           # 业务异常映射（per FR-007 统一 INVALID_CREDENTIALS）
        └── AccountWebExceptionAdvice.java   # @RestControllerAdvice，先于 mbw-shared.GlobalExceptionHandler
```

### Migrations

```text
mbw-account/src/main/resources/db/migration/account/
└── V2__create_account_register_by_phone.sql
```

> **DDL 真相源在 SQL 文件**，plan.md 只列字段语义，不写完整 DDL（避免 spec drift）。

## Domain Design

### 聚合根 Account

字段：`id (BIGINT) / phone (PhoneNumber) / status (ACTIVE) / createdAt (UTC) / updatedAt (UTC)`

不变式（编码到构造方法 / 业务方法）：

- ACTIVE 状态必有 ≥ 1 个 Credential（构造时强制传入 phoneCredential）
- 状态机 FR-004：`(无) → ACTIVE`，`AccountStateMachine.activate(Account)` 是唯一入口
- `phone` 不可变，构造后不允许 setter

### 值对象 PhoneNumber

```java
public record PhoneNumber(String e164) {
    public PhoneNumber {
        Objects.requireNonNull(e164);
        if (!e164.matches("^\\+861[3-9]\\d{9}$")) {
            throw new IllegalArgumentException("INVALID_PHONE_FORMAT");
        }
    }
    public String countryCode() { return "+86"; }  // M1 hardcode (CL-002)
    public String nationalNumber() { return e164.substring(3); }
}
```

### Credential 类型层级（CL-001）

```java
public sealed interface Credential permits PhoneCredential, PasswordCredential {}
public record PhoneCredential(AccountId account, PhoneNumber phone, Instant lastUsedAt) implements Credential {}
public record PasswordCredential(AccountId account, PasswordHash hash, Instant createdAt) implements Credential {}
```

### Domain Service

| 类 | 职责 | 对应 FR |
|----|------|--------|
| `PhonePolicy` | 校验 E.164 + 大陆段；构造 PhoneNumber 值对象 | FR-001 |
| `PasswordPolicy` | 校验密码强度规则 | FR-003 |
| `AccountStateMachine` | 受控状态转换；唯一入口 `activate()` | FR-004 |

## Repository Contracts（domain 纯接口）

```java
public interface AccountRepository {
    Optional<Account> findByPhone(PhoneNumber phone);
    boolean existsByPhone(PhoneNumber phone);  // FR-005 唯一性预检
    Account save(Account account);
}

public interface VerificationCodeRepository {
    void store(PhoneNumber phone, VerificationCode code, Duration ttl);
    Optional<VerificationCode> findByPhone(PhoneNumber phone);
    void incrementAttemptOrInvalidate(PhoneNumber phone, int maxAttempts);  // FR-002 attemptCount
    void delete(PhoneNumber phone);  // 验证成功 / maxAttempts 命中
}
```

infrastructure 实现：`AccountRepositoryImpl` 用 JPA + MapStruct；`RedisVerificationCodeRepository` 用 Spring Data Redis（Lettuce），Redis hash 结构存 `{codeHash, attemptCount, maxAttempts, createdAt}`。

## Application UseCase

### RequestSmsCodeUseCase

```text
input: phone
1. PhonePolicy.validate(phone)
2. RateLimitService.consumeOrThrow("sms:" + phone, 60s 1次)
3. RateLimitService.consumeOrThrow("sms:" + phone, 24h 10次)
4. RateLimitService.consumeOrThrow("sms:" + clientIp, 24h 50次)
5. accountRepo.existsByPhone(phone) →
   - false: 生成 6 位码 + BCrypt(cost=8) → store Redis (TTL 5min, attemptCount=0)
                                          → AliyunSmsClient.send(phone, Template A, code)
   - true:  AliyunSmsClient.send(phone, Template B, no code)  // FR-012
6. 返回 200（响应字节级一致 per US-3 AS-1）
```

### RegisterByPhoneUseCase

```text
input: phone, code, passwordOpt
1. dummyBcrypt.consumeTime()  // FR-013 入口对齐时延
2. PhonePolicy.validate(phone)
3. passwordOpt.ifPresent(PasswordPolicy::validate)  // FR-003
4. RateLimitService.consumeOrThrow("register:" + phone, 24h 5次失败锁 30min)
5. verificationCodeRepo.findByPhone(phone)
   .filter(vc → BCrypt.matches(code, vc.codeHash))
   .orElseThrow(InvalidCredentialsException::new)  // 触发 attempt++
6. tokenIssuer.signAccess(prospectiveAccountId)  // FR-011 先签 token 后写 DB
   tokenIssuer.signRefresh()
7. @Transactional:
   - account = AccountStateMachine.activate(new Account(phone))
   - accountRepo.save(account)
   - 若 passwordOpt.isPresent: save(new PasswordCredential(...))
   - 必 save(new PhoneCredential(...))
   - publish(AccountRegistered event)
8. catch DataIntegrityViolation → throw InvalidCredentialsException  // FR-005 + FR-007
9. 验证码消费删除
10. return RegisterByPhoneResult(accountId, accessToken, refreshToken)
```

`@Transactional(rollbackFor = Throwable.class)` per FR-011。

## DB Schema（V2 migration）

`account.account`：

| 列 | 类型 | 约束 |
|----|----|------|
| id | BIGINT | PK，IDENTITY |
| phone | VARCHAR(20) | NOT NULL UNIQUE |
| status | VARCHAR(16) | NOT NULL，CHECK in ('ACTIVE') |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT now() |
| updated_at | TIMESTAMPTZ | NOT NULL DEFAULT now() |

`account.credential`：

| 列 | 类型 | 约束 |
|----|----|------|
| id | BIGINT | PK，IDENTITY |
| account_id | BIGINT | NOT NULL，引用 account.account.id（同 schema FK 允许）|
| type | VARCHAR(16) | NOT NULL CHECK in ('PHONE','PASSWORD') |
| password_hash | VARCHAR(60) | NULLABLE（PHONE 类型时 NULL；PASSWORD 必填）|
| last_used_at | TIMESTAMPTZ | NULLABLE |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT now() |

索引：`uk_account_phone`（phone 唯一）；`idx_credential_account_id`。

## Cross-module dependencies

| 调用方 → 被调方 | 用途 |
|-----------|------|
| `mbw-account.application.RequestSmsCodeUseCase` → `mbw-shared.RateLimitService` | FR-006 三档 SMS 限流 |
| `mbw-account.application.RegisterByPhoneUseCase` → `mbw-shared.RateLimitService` | FR-006 register 失败计数 |
| `mbw-account.web.AccountWebExceptionAdvice` → `mbw-shared.web.GlobalExceptionHandler` | FR-007/010：模块特定 advice 优先（Order 较高），剩余兜底走 mbw-shared |
| `mbw-account.application` → `mbw-account.api.dto.AccountRegisteredEvent` → 其他模块 | 通过 Spring Modulith 事件总线（M1.1 内进程，M2 拆服务时不变 publish API）|

## Error codes（mbw-shared.api.error 内已存在 / 待补）

| Code | HTTP | 用途 |
|------|------|----|
| `INVALID_CREDENTIALS` | 401 | FR-007 统一码错 / 已注册 / 已作废 / 过期 |
| `INVALID_PHONE_FORMAT` | 400 | FR-001 |
| `INVALID_PASSWORD` | 400 | FR-003 |
| `RATE_LIMITED` | 429 | FR-006，含 `Retry-After` header |
| `SMS_SEND_FAILED` | 503 | FR-009 阿里云重试耗尽 |

需在 `mbw-shared.api.error.SystemErrorCode` 补 `SMS_SEND_FAILED`；其余可能已有，落实时核对。

## Configuration（application.yml 增量）

```yaml
mbw:
  auth:
    jwt:
      secret: ${MBW_AUTH_JWT_SECRET:#{null}}   # FR-008，缺失 fail-fast
      access-ttl: 15m
      refresh-ttl: 30d
  account:
    register:
      sms-template-verify: ${SMS_TEMPLATE_VERIFY_CODE}   # 阿里云模板 ID
      sms-template-registered-notify: ${SMS_TEMPLATE_REGISTERED_NOTIFY}
      bcrypt-cost: 12
      verification-code-ttl: 5m
      verification-code-max-attempts: 3
spring:
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
```

## Performance / Resilience

- SC-001 P95 ≤ 800ms：bcrypt cost=12 ~150ms 是主要时间窗口；其余 DB/Redis 各 < 50ms 充裕
- SC-004 时延差 ≤ 50ms：FR-013 入口 dummy bcrypt 保证；测试 1000 次 P95 对比
- SMS gateway 失败：FR-009 异步重试 2 次，熔断后 `SMS_SEND_FAILED`（用 Resilience4j @Retry 注解，cost 低）
- Redis 失败：fail-closed（限流不可用时拒绝服务）— 比 fail-open 更安全，符合"不知道就拒绝"原则

## Test Strategy

| 层 | 框架 | 覆盖 |
|----|------|----|
| Domain unit test | JUnit 5 + AssertJ | 值对象 / Domain Service / 状态机所有规则 |
| Application unit test | JUnit 5 + Mockito | UseCase 各分支 + 依赖 mock |
| Repository IT | Testcontainers（PG / Redis）| `AccountRepositoryImpl` 真 DB 验证；唯一约束竞态测试（SC-003）|
| Web IT | Spring `@WebMvcTest` + MockMvc | Controller / Springdoc / ProblemDetail 形态 |
| End-to-end IT | Testcontainers（PG + Redis + MockServer for SMS）| User Stories 1/2/3 + SC 全套 |
| ArchUnit | 已有 mbw-app 测试 | domain 不依赖 framework；跨模块只走 api |
| Timing test | Testcontainers + 1000 次循环 | SC-004 已注册 vs 未注册 P95 时延差 ≤ 50ms |

## Risk / Open items

- **阿里云短信模板审批**（spec FR-012）：Template B 提前送审；T6 task 阻塞前置
- **`spring.redis` vs `spring.data.redis`**：Spring Boot 3.x 后者为正确路径；application.yml 用 `spring.data.redis`（实施时核对版本）
- **bucket4j-redis 依赖**（per ADR-0011 amendment）：`mbw-shared` 需先迁移到 Redis backend，作为本 use case 实施前置；建议放 PR-3c 第一个 task

## Out of scope（本 plan）

- 业务规则细节已在 spec.md，不重复
- 测试用例编写（在 tasks.md / implement 阶段）
- 完整 SQL DDL（在 V2__*.sql）
- 监控告警阈值（M2 引入 Sentry/OTel 时配）
