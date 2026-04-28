# Tasks: Register by Phone

**Spec**: [`spec.md`](./spec.md) | **Plan**: [`plan.md`](./plan.md)
**Date**: 2026-04-28

## 推进规则

- 每个 task 30min-2h，单次 commit `feat(account): <summary>`（housekeeping 用 `chore`/`docs`）
- **测试与实现绑定同一 task**（TDD 红绿循环：写失败测试 → 实现 → 绿）
- 每个 task 进入 Plan Mode 审批后再写代码
- 按依赖串行；标 `[par]` 的可并行
- T0~T2 是前置基础设施（在 mbw-shared）；T3+ 在 mbw-account 业务模块

## 前置 / 跨模块

| ID | 层 | Task | 估时 | 依赖 |
|----|-----|------|------|------|
| **T0** | [Infra mbw-shared] | RateLimitService 迁移 ConcurrentHashMap → bucket4j-redis（per ADR-0011 amendment）。pom.xml 加 `bucket4j_jdk17-redis`；删除 `ConcurrentHashMap` impl，换 `LettuceBasedProxyManager`；保留同 API 签名（`consumeOrThrow` / `reset`）；fail-closed 策略；Testcontainers Redis IT 验证 | 2h | — |
| **T1** | [Infra mbw-shared] | 阿里云短信 SDK 引入 + `SmsClient` 接口（在 `mbw-shared.api.client.SmsClient`）+ `AliyunSmsClient` impl（在 mbw-app 配置）+ Resilience4j @Retry 配置 + MockServer IT 模拟 SMS gateway | 1.5h | — [par with T0] |
| **T2** | [Infra mbw-shared] | `SmsCodeService`（在 mbw-shared 或新建 mbw-sms 模块？建议先放 mbw-shared.infrastructure，M1.2 拆分时再独立模块）— 封装 Redis 存验证码 + Lua atomic + SETNX；提供 `generateAndStore(phone)` / `verify(phone, code)` API。Testcontainers Redis IT | 2h | — [par with T0/T1] |

## Domain 层（mbw-account）

| ID | 层 | Task | 估时 | 依赖 |
|----|-----|------|------|------|
| T3 | [Domain] | `PhoneNumber` / `PasswordHash` / `AccountId` / `VerificationCode` 值对象 + AccountStatus enum + 单元测试（FR-001 / FR-003 边界场景） | 1h | — |
| T4 | [Domain] | `Credential` sealed interface + `PhoneCredential` / `PasswordCredential` records + 测试 | 30min | T3 |
| T5 | [Domain] | `Account` 聚合根 + `AccountStateMachine.activate()` + 不变式测试（构造时强制 phone credential / phone 不可变 / 状态机唯一入口） | 1.5h | T4 |
| T6 | [Domain] | `PhonePolicy` / `PasswordPolicy` Domain Service + 单元测试（FR-001 / FR-003 全部分支） | 1h | T3 [par with T4/T5] |
| T7 | [Domain] | `AccountRepository` / `VerificationCodeRepository` 接口 + Mock 单元测试演示用法 + ArchUnit 测试断言 domain 不依赖 Spring/JPA | 1h | T5 |

## Infrastructure 层（mbw-account）

| ID | 层 | Task | 估时 | 依赖 |
|----|-----|------|------|------|
| T8 | [Infra] | Flyway `V2__create_account_register_by_phone.sql`：account 表 + credential 表 + 3 个索引（uk_account_phone / idx_credential_account_id / **uk_credential_account_type**） | 30min | — |
| T9 | [Infra] | `AccountJpaEntity` / `CredentialJpaEntity` + JPA repos + `AccountRepositoryImpl` + `AccountMapper` (MapStruct) + Testcontainers PG IT（含 SC-003 同 phone 10 并发竞态测试 + UNIQUE 约束触发）| 2h | T7 + T8 |
| T10 | [Infra] | `RedisVerificationCodeRepository` + Lua 脚本（HINCRBY + 条件 DEL）+ SETNX store + Testcontainers Redis IT 含**Lua 原子性并发测试**（10 并发同 phone 验证 attemptCount 准确） | 2h | T7 [par with T9] |
| T11 | [Infra] | `BCryptPasswordHasher` + `JwtTokenIssuer`（Nimbus JOSE access/refresh）+ `JwtProperties` (`@Validated @NotBlank` fail-fast on absent secret) + 单元测试 | 1.5h | — [par with T9/T10] |
| T12 | [Infra] | `TimingDefenseExecutor.executeInConstantTime(target, body)` + 单元测试（验证 try-finally pad + 异常仍抛出 + target 时间一致性） | 1h | — [par with T11] |

## Application 层

| ID | 层 | Task | 估时 | 依赖 |
|----|-----|------|------|------|
| T13 | [App] | `RequestSmsCodeUseCase` + 4 档限流（FR-006）+ Template A/B 分发（FR-012，已注册查询走 existsByPhone）+ Mockito 单元测试覆盖 6 个分支（未注册成功 / 已注册 Template B / 限流 60s / 限流 24h / IP 限流 / SMS gateway fail）| 2h | T1 + T2 + T9 |
| T14 | [App] | `RegisterByPhoneUseCase` 整体包在 `TimingDefenseExecutor.executeInConstantTime(400ms, ...)` + FR-011 先签 token 后写 DB + DataIntegrityViolation 兜底 + Mockito 单元测试覆盖所有路径 | 2h | T9 + T10 + T11 + T12 |

## Web 层

| ID | 层 | Task | 估时 | 依赖 |
|----|-----|------|------|------|
| T15 | [Web] | `AccountRegisterController` 2 endpoints（POST `/sms-codes` / POST `/register-by-phone`）+ Request/Response records + Jakarta Validation + Springdoc OpenAPI 注解（含 ProblemDetail 错误响应描述）+ MockMvc IT | 1.5h | T13 + T14 |
| T16 | [Web] | `AccountWebExceptionAdvice`（@RestControllerAdvice，`@Order` 高于 mbw-shared.GlobalExceptionHandler）— 业务异常统一映射 INVALID_CREDENTIALS / INVALID_PHONE_FORMAT / INVALID_PASSWORD / RATE_LIMITED / SMS_SEND_FAILED → ProblemDetail | 1h | T15 |

## End-to-end + Contract

| ID | 层 | Task | 估时 | 依赖 |
|----|-----|------|------|------|
| T17 | [E2E] | Testcontainers 全栈 IT（PG + Redis + MockServer SMS）覆盖 spec.md User Stories 1/2/3 全 9 个 Acceptance Scenarios + SC-001/002/003/005 | 2.5h | T15 + T16 |
| T18 | [E2E timing] | **SC-004 timing defense 测试**：1000 次循环对比已注册 vs 未注册 phone 的 P95 时延差 ≤ 50ms。允许小批量预热 + JVM warm-up | 1.5h | T17 |
| T19 | [Contract] | OpenAPI spec verify（`/v3/api-docs` JSON snapshot 测试，防 API 契约漂移）+ ArchUnit 完整边界断言（domain 不依赖 framework / 跨模块只走 api 包 / web 不直连 infrastructure） | 1h | T15 |

## 总览

- **19 tasks，约 28h 总工作量**（含测试，TDD 红绿循环时间已计入估时）
- **关键路径**：T0/T1/T2 → T3 → T5 → T7 → T9/T10 → T14 → T15 → T17 → T18
- **并行机会**：
  - T0/T1/T2 同时起（不同子模块文件）
  - T4/T6 在 T3 后并行
  - T9/T10/T11/T12 在依赖满足后并行（不同文件）
- **第一个可观察绿光**：T5 完成时（Account 聚合根 + 状态机 + 单元测试全绿，纯 domain 逻辑可证业务规则）

## TDD 节奏（每个 task 内）

```text
1. 写失败测试（红）— 描述要实现的不变式 / 业务规则
2. 跑 mvn test 看红 — 确认测试真的会失败
3. 写最小实现（绿）— 不过度工程，只让测试过
4. 跑 mvn test 看绿
5. Spotless apply + Checkstyle check
6. 重构（仍绿）— 命名 / 抽取 / 简化
7. git commit feat(account): <task summary>
```

## 风险点（再次提示）

- **T1 阿里云短信模板审批**：T13 实施前必须先送审 Template A + Template B（1-2 工作日），未审下来时 T13 内已注册分支临时不发任何 SMS（保留代码占位 + TODO，spec.md FR-012 已记录）
- **T0 RateLimitService 迁移**：替换 mbw-shared 已有实现，所有调用方 API 不变，但 Testcontainers Redis IT 必须真起 Redis 验证 fail-closed 行为
- **T18 timing 测试**：CI 跑 timing test 容易因为 runner 负载抖动假阳性；考虑加 retry-on-failure 容忍机制（最多 3 次取最优 P95），仍超 50ms 才 fail

## 下一步

`/speckit.analyze` — 跨 spec/plan/tasks 一致性 + 宪法合规 + Findings Table CRITICAL/HIGH 必修后再 implement
