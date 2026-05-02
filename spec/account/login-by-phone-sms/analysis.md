# Cross-Artifact Analysis: Login by Phone SMS

**Run**: 2026-05-02 (本 PR 内)
**Inputs**: [`spec.md`](./spec.md) + [`plan.md`](./plan.md) + [`tasks.md`](./tasks.md) + [`.specify/memory/constitution.md`](../../../.specify/memory/constitution.md)

> 本 use case 大量复用 register-by-phone 已落基础设施。本 analysis 重点核对**新引入决策**与现有约定一致性。

## Coverage Matrix（FR / SC → Task）

| Spec ID | Plan locus | Task | 状态 |
|---------|-----------|------|------|
| FR-001 复用 register FR-001/002 | (无新代码) | (跨 use case 共享) | ✅ |
| FR-002 入参 + 响应 + 无密码 | LoginByPhoneSmsCommand/Result | T5 | ✅ |
| FR-003 仅 ACTIVE 可登录 | AccountStateMachine.canLogin | T3 + T6 | ✅ |
| FR-004 last_login_at 更新 | Account.markLoggedIn + AccountRepository.updateLastLoginAt | T1 + T2 + T4 + T6 | ✅ |
| FR-005 限流（含 login:`<phone>` 新 bucket） | RateLimitService（既有） + UseCase 调用 | T6 | ✅ |
| FR-006 INVALID_CREDENTIALS 统一 | InvalidCredentialsException + AccountWebExceptionAdvice（既有） | T6 + T9 | ✅ |
| FR-007 token 签发（refresh 持久化推迟到 1.3） | TokenIssuer（既有） | T6 | ✅ |
| FR-008 RFC 9457 ProblemDetail | mbw-shared.GlobalExceptionHandler（既有） | (复用) | ✅ |
| FR-009 SMS Template C + purpose 字段 | RequestSmsCodeUseCase + endpoint 扩展 | T7 + T10 | ✅ |
| FR-010 原子性 @Transactional | LoginByPhoneSmsUseCase | T6 | ✅ |
| FR-011 Timing defense（复用 register） | TimingDefenseExecutor（既有 register T12） | T6 | ✅ |
| FR-012 V3__add_account_last_login_at.sql | Flyway migration | T0 + T4 | ✅ |
| SC-001 P95 ≤ 600ms | T11 内 explicit assertion（per A1 待修） | T11 | ⚠️ A1 |
| SC-002 100 并发 0 错 | LoginByPhoneSmsE2EIT | T11 | ✅ |
| SC-003 timing P95 diff ≤ 50ms | LoginByPhoneSmsTimingDefenseIT | T12 | ✅ |
| SC-004 限流 4 规则 | E2E 内覆盖 | T11 | ✅ |
| SC-005 跨 use case 防枚举 | CrossUseCaseEnumerationDefenseIT | T13 | ✅ |

## Constitution Compliance

| 原则 | 检查 | 状态 |
|------|------|------|
| I. Modular Monolith | 工作仅在 mbw-account；不引入新跨模块依赖 | ✅ |
| II. DDD 5-Layer | 文件清单分层严格；domain 零 framework 依赖 | ✅ |
| III. TDD Strict | tasks.md 每个实现 task 有红绿循环测试绑定 | ✅ |
| IV. SDD via Spec-Kit | 4 文件产出（spec / plan / tasks / 本 analysis） | ✅ |
| V. DB Schema Isolation | account schema 内 expand-only 加列 | ✅ |
| VI. Expand-Migrate-Contract | 不适用（纯增列，per spec.md CL-004 + CLAUDE.md "跳步条件 1" 满足）| ✅ |

## Findings

| ID | 严重度 | 维度 | 描述 | 建议动作 |
|----|--------|------|------|---------|
| **A1** | **HIGH** | Coverage gap | SC-001（P95 ≤ 600ms）未在 tasks.md 显式 assertion task；T11 E2E 隐式覆盖但无独立性能验收 | T11 内显式加 P95 ≤ 600ms 断言，或 拆 T11b 性能验收 sub-task；与 register-by-phone analysis A1 同 pattern |
| **A2** | **MEDIUM** | Reuse pattern | tasks.md T9 提 "AuthController 新建（与既有 AccountRegisterController 平行）"，但 register `/sms-codes` endpoint 在 AccountRegisterController 内（路径 `/api/v1/accounts/sms-codes`）。purpose 字段扩展应**保留** sms-codes 在原 controller，AuthController 仅承接 4 个 auth-* endpoint | tasks.md T10 已正确（继续在 AccountRegisterController 扩展），与 T9 描述无冲突。**确认**：AuthController 不接管 /sms-codes |
| **A3** | **MEDIUM** | Future use case 协调 | spec.md FR-007 + CL-003 推迟 RefreshTokenRecord 持久化到 Phase 1.3；但 1.1 落地后到 1.3 落地之间，已签的 refresh token 在客户端是"无 server 校验的孤儿"。M1.2 plan 接受此窗口期，但 doc 应显式 | spec.md FR-007 + CL-003 已说明窗口期接受；implement 阶段无 action；M1.2 plan 已记录 |
| **A4** | **LOW** | Template C 阿里云审批前置 | spec.md FR-009 + Assumption A-006 提"未审下来时未注册 phone 不发 SMS + pad time"；但 implement 阶段 T7 未明确 fallback path 测试 | tasks.md T7 已含 `should_pad_time_when_template_C_unavailable_and_phone_not_registered()`；✅ |
| **A5** | **LOW** | Cross-use-case test extension | T13 CrossUseCaseEnumerationDefenseIT 是 SC-005 新引入测试；register-by-phone E2E 不感知本测；本 PR 内创建 OK，但未来 use case 加入应**扩展**而非 fork 本测 | 本 use case 内不处理；M3 复评点：是否抽 `CrossUseCaseEnumerationDefenseIT` 到 mbw-shared 层 |
| **A6** | **LOW** | Account.markLoggedIn 不变式 | plan.md 设计的 `markLoggedIn(now)` 对 non-ACTIVE 抛 IllegalStateException，但 UseCase 内已先调 `stateMachine.canLogin()` → 不变式 throw 路径正常代码不会触发 | 单测 T1 仍要写"non-ACTIVE 抛异常"作 invariant guard，避免未来重构破坏；✅ T1 已含 |
| **A7** | **LOW** | last_login_at 索引 | spec.md FR-012 + plan.md 不加 idx_account_last_login_at；但未来 "近 N 天活跃用户" use case 加入时需要 | 本 use case 不加索引（避免预 over-engineer）；M3 内测前 use case 引入时再加 |

## CRITICAL / HIGH 修正项汇总

**1 项 HIGH（A1）**：T11 显式加 P95 ≤ 600ms 断言。

**实施时机**：implement PR (PR 2) 内 T11 task 描述补充，不阻塞本 spec/plan/tasks PR。

**0 CRITICAL**。

## 建议下一步

本 PR（spec + plan + tasks + 本 analysis）通过后：

- merge 入 main
- 开 **PR 2: feat(account): impl login-by-phone-sms** —— 按 tasks.md T0~T14 实施，每个 task 进 Plan Mode 审批，TDD 红绿循环

PR 2 内显式落 A1（T11 P95 assertion），其他 A2-A7 是 doc / future 备注，不阻塞。
