# Cross-Artifact Analysis: Login by Password

**Run**: 2026-05-02 (本 PR 内)
**Inputs**: [`spec.md`](./spec.md) + [`plan.md`](./plan.md) + [`tasks.md`](./tasks.md) + [`.specify/memory/constitution.md`](../../../.specify/memory/constitution.md)

> 本 use case 大量复用 1.1 + register 已落基础设施。本 analysis 重点核对**新引入决策**与现有约定一致性。

## Coverage Matrix（FR / SC → Task）

| Spec ID | Plan locus | Task | 状态 |
|---------|-----------|------|------|
| FR-001 phone E.164（复用 register FR-001） | (无新代码) | (跨 use case 共享) | ✅ |
| FR-002 入参 + 响应 | LoginByPasswordCommand/Result | T1 | ✅ |
| FR-003 仅 ACTIVE 可登录（复用 1.1） | AccountStateMachine.canLogin（既有） | T2 | ✅ |
| FR-004 last_login_at 更新（复用 1.1） | AccountRepository.updateLastLoginAt（既有 1.1） | T2 | ✅ |
| FR-005 INVALID_CREDENTIALS 统一 | InvalidCredentialsException + AccountWebExceptionAdvice（既有） | T2 + T4 | ✅ |
| FR-006 不校验密码强度 | UseCase 内**不调** PasswordPolicy | T2 | ✅ |
| FR-007 限流（共享 login:`<phone>` + 新 auth:`<ip>`） | RateLimitService 调用（既有） | T2 | ✅ |
| FR-008 token 签发（refresh 持久化推 1.3） | TokenIssuer（既有） | T2 | ✅ |
| FR-009 入口级 BCrypt verify | TimingDefenseExecutor.executeWithBCryptVerify | T0 + T2 | ✅ |
| FR-010 原子性 @Transactional | LoginByPasswordUseCase | T2 | ✅ |
| FR-011 RFC 9457 ProblemDetail（复用 register） | mbw-shared.GlobalExceptionHandler（既有） | (复用) | ✅ |
| FR-012 不发 SMS | (UseCase 不调 SmsCodeService) | T2 | ✅ |
| SC-001 P95 ≤ 500ms | T5 内 explicit assertion（per A1 待修） | T5 | ⚠️ A1 |
| SC-002 100 并发 0 错 | LoginByPasswordE2EIT | T5 | ✅ |
| SC-003 timing P95 diff ≤ 50ms（3 场景） | LoginByPasswordTimingDefenseIT | T6 | ✅ |
| SC-004 限流 3 规则 | E2E 内覆盖 | T5 | ✅ |
| SC-005 跨 use case 防枚举 | CrossUseCaseEnumerationDefenseIT 扩展 | T7 | ✅ |

## Constitution Compliance

| 原则 | 检查 | 状态 |
|------|------|------|
| I. Modular Monolith | 工作仅在 mbw-account；不引入新跨模块依赖 | ✅ |
| II. DDD 5-Layer | 文件清单分层严格 | ✅ |
| III. TDD Strict | tasks.md 每个实现 task 有红绿循环测试绑定 | ✅ |
| IV. SDD via Spec-Kit | 4 文件产出（spec / plan / tasks / 本 analysis） | ✅ |
| V. DB Schema Isolation | 无 schema 变更 | ✅ |
| VI. Expand-Migrate-Contract | 不适用（无 schema 变更） | ✅ |

## Findings

| ID | 严重度 | 维度 | 描述 | 建议动作 |
|----|--------|------|------|---------|
| **A1** | **HIGH** | Coverage gap | SC-001（P95 ≤ 500ms）未在 tasks.md 显式 assertion task；T5 E2E 隐式覆盖但无独立性能验收 | T5 内显式加 P95 ≤ 500ms 断言；与 1.1 analysis A1 / register-by-phone analysis A1 同 pattern |
| **A2** | **MEDIUM** | hashLookup 性能 | plan.md UseCase 设计中 hashLookup 内 + onMatch 内**两次** `findByPhone` DB 查询。Happy path 多花 ~5-10ms（PG indexed lookup） | M2 复评点：抽 ThreadLocal 或参数传递。本 PR 接受现状（避免过早优化破坏时延对齐）；implement 时在 plan.md / 注释明示这是"故意双查" |
| **A3** | **MEDIUM** | TimingDefenseExecutor 通用化 | T0 扩展 `executeWithBCryptVerify` 与既有 `execute(Supplier)` 共存。1.4 logout-all 不需任何 timing defense（access token 鉴权已经 invariant 时延） — 不会再扩展。**M2 评估**抽 `TimingDefenseStrategy` 接口让 executor 多策略组合 | 本 PR 不抽（仅 2 用法），M2 评估；implement 阶段加 TODO 注释 |
| **A4** | **LOW** | LoginResponse 字段重复 | 1.1 + 1.2 + 1.3 + 1.4（如有） LoginResponse 字段一致：`{accountId, accessToken, refreshToken}`。各 use case 各一份 record？还是共享 `mbw-account.application.result.AuthResult`？ | 本 PR 各自一份（避免过早抽象）；Phase 1.4 完成后回望评估抽共享 record |
| **A5** | **LOW** | clientIp 提取一致性 | T4 controller 用 `HttpServletRequest.getRemoteAddr()`，但 register-by-phone / 1.1 是否都已用此方式？反向代理 X-Forwarded-For 配置一致？ | implement 阶段 grep 既有代码核对一致性；plan.md 已提到 `server.forward-headers-strategy=native` |
| **A6** | **LOW** | DUMMY_HASH 共享 | T0 引用 register-by-phone FR-013 既建的 static final DUMMY_HASH。**确认**位置：在 `TimingDefenseExecutor` 内 vs 单独 `mbw-account.domain.service.PasswordHasher` 类？ | implement 时确认 DUMMY_HASH 在 TimingDefenseExecutor 内或可访问 static field；避免重复定义 |
| **A7** | **LOW** | sms+password 共享 login:`<phone>` 失败 bucket 跨 use case 一致性 | spec CL-001 决策共享 bucket，但实施方需在 implement 时确认 1.1 LoginByPhoneSmsUseCase 也走相同 Redis key（不是 `login-sms:<phone>` 这种分裂） | implement 阶段 grep 1.1 实现确认 key 完全一致；E2E 测试 T5 / 1.1 T11 跨用例验证 |

## CRITICAL / HIGH 修正项汇总

**1 项 HIGH（A1）**：T5 显式加 P95 ≤ 500ms 断言。

**实施时机**：implement PR (PR 2) 内 T5 task 描述补充。

**0 CRITICAL**。

## 建议下一步

本 PR（spec + plan + tasks + analysis）通过后：

- merge 入 main
- 可继续 Phase 1.3 docs（refresh-token，最复杂，新表 RefreshTokenRecord）；或先做 Phase 1.4 docs（logout-all，依赖 1.3 schema）
- 实施 PR 2 留作专门 session（与 1.1 PR 2 一并做更高效，可共享 plan mode 调度）
