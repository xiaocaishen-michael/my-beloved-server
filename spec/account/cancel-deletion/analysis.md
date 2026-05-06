# Analysis: Cancel Deletion

**Spec**: [`./spec.md`](./spec.md) · **Plan**: [`./plan.md`](./plan.md) · **Tasks**: [`./tasks.md`](./tasks.md)
**Created**: 2026-05-06
**Reviewer**: SDD `/speckit.analyze`（跨 spec / plan / tasks / constitution 一致性扫描）

> 与 [`../delete-account/analysis.md`](../delete-account/analysis.md) 联合阅读以确认状态机闭环。

## Coverage Matrix（FR / SC → Task）

| FR | Plan 段 | Task | 测试覆盖 |
|---|---|---|---|
| FR-001 发码 endpoint | § Endpoint 1 | T5 | T5 unit + T6 E2E |
| FR-002 撤销 endpoint | § Endpoint 2 | T5 | T5 unit + T6 E2E |
| FR-003 无鉴权 | § 流程图（无 JwtAuthFilter） | T5 | 既有 SecurityConfig 不动 |
| FR-004 SMS code 持久化 | 复用 delete-account V8 + AccountSmsCodeRepository | T0 (enum) + delete-account T4 | T6 E2E |
| FR-005 限流（4 维度，phone hash） | § Endpoint 1+2 流程 | T3 + T4 | T3 + T4 unit + T6 E2E |
| FR-006 transition 原子性 | § Endpoint 2 流程 | T4 | T4 unit + T7 concurrency |
| FR-007 错误响应 | § 反枚举设计 | T5 + T8 | T8 字节级断言 |
| FR-008 outbox 事件 | § 事件流 | T1 + T4 | T6 SC 段 |
| FR-009 OpenAPI | § API 契约 | T9 | OpenApiSnapshotIT |
| FR-010 phone-sms-auth 行为不变 | § 反模式 | — | 既有 phone-sms-auth IT 不动 |
| FR-011 埋点 placeholder | § Endpoint 2 流程 step 11 | T4 | log assertion (T4 unit) |

| SC | Task |
|---|---|
| SC-001 发码 P95（FROZEN ≤ 1.5s / 非 FROZEN ≤ 200ms） | 不显式 IT，靠监控 |
| SC-002 撤销 P95 ≤ 300ms | 同上 |
| SC-003 transition 原子性 | T7 `should_keep_state_unchanged_when_TokenIssuer_fails` |
| SC-004 反枚举 | T6 US2 + T8 |
| SC-005 限流准确性 | T6 US3 + T3/T4 unit |
| SC-006 状态机不变量 | T6 US4 freeze_until 抢跑 + T0 unit |
| SC-007 与 phone-sms-auth schema 解耦 | T6 US1 LoginResponse 字节级一致断言 + T8 problem.type 区分断言 |
| SC-008 scheduler 抢跑 | T6 US4 AS-2 + T7 race scenario |
| SC-009 ArchUnit | 既有 ModuleStructureTest |
| SC-010 OpenAPI snapshot | T9 |

**Coverage 评估**：所有 FR / SC 均有 task + 测试覆盖；性能 SLO 不强制 IT，与 phone-sms-auth/delete-account 一致 pattern。

## Constitution Compliance

| 原则 | 检查 | 结果 |
|---|---|---|
| Modular Monolith | 仅 mbw-account 内改动 + AccountDeletionCancelledEvent 在 api.event 包 | ✅ |
| DDD 5-Layer | 严格分层 | ✅ |
| TDD Strict | 每 task 红绿循环；record 例外标注 | ✅ |
| Repository pattern | 复用 delete-account 落地的 Repository 接口扩展 | ✅ |
| No cross-schema FK | 无新 FK | ✅ |
| Flyway immutable | 无新 migration（复用 delete-account V7 + V8） | ✅ |
| OpenAPI 单一真相源 | spec.md 不重复 OpenAPI 字节 | ✅ |
| No password / token in logs | log 仅 accountId + daysRemaining | ✅ |
| spec.md 3 段官方模板 | ✅ | ✅ |
| Anti-pattern 反 spec drift | 与 account-state-machine.md 完全对齐；与 delete-account spec 共享 enum/event/migration 路径，无 redefinition | ✅ |

## Findings

| 严重度 | ID | Location | 描述 | 建议 |
|---|---|---|---|---|
| MEDIUM | F-001 | spec CL-001 + Edge Cases | SMS 服务挂时 FROZEN 命中返 503 vs 非 FROZEN 返 200 — 攻击者可推测 phone 是否 FROZEN（小信息泄露） | 文档化 + 限流缓解；接受小泄露换撤销 UX 可行性。文案模糊化（"系统繁忙"不暴露 SMS 字样） |
| LOW | F-002 | spec FR-011 | 埋点接入仅 placeholder | 同 delete-account F-001 — M2+ retrofit |
| LOW | F-003 | plan § 复用 step | `LoginResponse` schema 复用 — 若未来 phone-sms-auth response 增字段 cancel-deletion 自动跟进，无版本控制 | 接受 — schema 演化由 OpenAPI 单一真相源保证一致 |
| INFO | F-004 | spec FR-008 | `AccountDeletionCancelledEvent` 当前无 listener 消费 | 接受 — outbox 设计预留 M2+ pkm 等订阅；与 delete-account RequestedEvent 对称 |
| INFO | F-005 | plan § Account.markActiveFromFrozen | invariant 检查 freezeUntil != null 防御 V7 之前的"无 freeze_until 字段 FROZEN 行" — M1.3 时点不可能存在（无生产用户）| 接受 — 防御性写法不增成本 |

**无 CRITICAL / HIGH** finding。

MEDIUM F-001 是被 spec CL-001 显式接受的小信息泄露，已文档化 + 限流缓解；不视为阻塞项。

## CRITICAL / HIGH 修正项汇总

无。

## 建议下一步

1. **本 docs PR merge 后** + delete-account docs PR merge 后：进入 implementation phase
2. **Implementation 顺序强制**：delete-account impl PR 必先 merge → cancel-deletion impl PR 才可起（前置：V7 + V8 + AccountSmsCodePurpose enum 物理结构 + AccountSmsCodeRepository.findActiveByPurposeAndAccountId）
3. **共用前置**：1.4 logout-all + 1.3 refresh-token + account-profile 全已 ship
4. **PRD § 5.5 文本同步**：与 delete-account analysis 第 3 项合并到同一 PR 修订 PRD（不阻塞本 spec PR）
5. **F-001 的客户端文案对齐**：app 仓 cancel-deletion 流程 PR 需把 503 响应映射为"系统繁忙，请稍后再试"通用文案，不暴露 SMS 字样（此项跨仓约束）

## 与 delete-account 的协同

见 [`../delete-account/analysis.md`](../delete-account/analysis.md) § 与 cancel-deletion 的协同 — 同一表格，对称视角。

**关键不变量**（联合断言）：

- ACTIVE → FROZEN → ACTIVE round-trip 必须可重复（无次数限制，仅受频率限流）
- ACTIVE → FROZEN → ANONYMIZED 一旦走 ANONYMIZED 即永久（cancel-deletion 必返 401）
- freeze_until > now 是 cancel transition 的硬约束（防 scheduler-cancel race） — SC-008 强制
- 两 use case 的 401 响应 problem.type 必须不同（防意外耦合，便于 client 区分错误来源） — T8 强制
