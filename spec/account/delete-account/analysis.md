# Analysis: Delete Account

**Spec**: [`./spec.md`](./spec.md) · **Plan**: [`./plan.md`](./plan.md) · **Tasks**: [`./tasks.md`](./tasks.md)
**Created**: 2026-05-06
**Reviewer**: SDD `/speckit.analyze`（跨 spec / plan / tasks / constitution 一致性扫描）

> 本 analysis 与配套 [`../cancel-deletion/analysis.md`](../cancel-deletion/analysis.md) 联合阅读以确认状态机闭环一致性。

## Coverage Matrix（FR / SC → Task）

| FR | Plan 段 | Task | 测试覆盖 |
|---|---|---|---|
| FR-001 发码 endpoint | § Endpoint 1 | T9 | T9 unit + T10 E2E |
| FR-002 提交 endpoint | § Endpoint 2 | T9 | T9 unit + T10 E2E |
| FR-003 鉴权（统一 401） | § Web layer + 复用 JwtAuthFilter | T9 | T9 unit + T10 E2E |
| FR-004 SMS code 持久化（purpose 区分） | § Migration V8 + Infra T4 | T1 + T4 | T4 IT + T10 E2E |
| FR-005 限流（5 维度） | § Endpoint 1+2 流程 | T7 + T8 | T7 + T8 unit + T10 E2E |
| FR-006 transition 原子性 | § Endpoint 2 流程 + § 复用 logout-all | T8 | T8 unit + T11 concurrency |
| FR-007 错误响应 | § 反枚举设计 | T9 + T12 | T12 字节级断言 |
| FR-008 outbox 事件 | § 事件流 | T3 + T8 | T10 E2E SC-010 |
| FR-009 OpenAPI | § API 契约 | T13 | OpenApiSnapshotIT |
| FR-010 phone-sms-auth 行为不变 | § 反模式 | — | 既有 phone-sms-auth IT 不动 |
| FR-011 埋点 placeholder | § Endpoint 2 流程 step 11 | T8 | log assertion (T8 unit) |

| SC | Task |
|---|---|
| SC-001 发码 P95 ≤ 1.5s | 不显式 IT 测试（per L2 SLO，与 phone-sms-auth 同 source）|
| SC-002 提交 P95 ≤ 300ms | 同上（人工监控）|
| SC-003 transition 原子性 | T11 concurrency `should_keep_all_state_unchanged_when_revokeAllForAccount_fails` |
| SC-004 token 失效 | T10 US1 AS-3/AS-4 |
| SC-005 限流准确性 | T10 US4 + T7/T8 unit |
| SC-006 反枚举 | T10 US2 + T12 |
| SC-007 状态机不变量 | T10 freezeUntil 容差断言 + T2 unit |
| SC-008 ArchUnit | 既有 ModuleStructureTest |
| SC-009 OpenAPI snapshot | T13 |
| SC-010 outbox 写入 | T10 SC-010 case |

**Coverage 评估**：所有 FR / SC 均有 task + 测试覆盖；SC-001 / SC-002 性能 SLO 不强制 IT，靠生产监控（per refresh-token analysis 同 pattern）。

## Constitution Compliance

| 原则 | 检查 | 结果 |
|---|---|---|
| Modular Monolith | 仅 mbw-account 内改动 + AccountDeletionRequestedEvent 在 api.event 包 | ✅ |
| DDD 5-Layer | api.event / domain / application / infrastructure / web 严格分层 | ✅ |
| TDD Strict | 每 task 红绿循环；record / pure DTO 例外标注清晰 | ✅ |
| Repository pattern | AccountSmsCodeRepository 接口 + JpaImpl 分离 | ✅ |
| No cross-schema FK | 本 spec 不引入新 FK | ✅ |
| Flyway immutable | V7 + V8 全新文件 | ✅ |
| OpenAPI 单一真相源 | spec.md 不重复 OpenAPI 字节，由 Springdoc 推导 | ✅ |
| No password / token in logs | log 仅 accountId + freezeUntil | ✅ |
| spec.md 3 段官方模板 | 含 User Scenarios & Testing / Functional Requirements / Success Criteria；Clarifications/Assumptions/Out of Scope 是补充段，不是新发明 | ✅ |
| Anti-pattern 反 spec drift | 与 account-state-machine.md 完全对齐（无 redefinition） | ✅ |

## Findings

| 严重度 | ID | Location | 描述 | 建议 |
|---|---|---|---|---|
| LOW | F-001 | spec FR-011 / plan step 11 | 埋点接入仅 placeholder（commented TODO），无运行时实现 | 接受 — 埋点模块 M2+ 引入后单独 PR retrofit |
| LOW | F-002 | spec Out of Scope | 未提"注销前数据导出"（GDPR 被遗忘权配套）| 接受 — M3+ 合规框架内评估 |
| INFO | F-003 | plan § Migration | V7 + V8 在同 PR ship 而非分两次 — 有违 expand-migrate-contract 严格分步 | 接受 — server CLAUDE.md § 五"无真实用户 + dev/staging 跳步" 条件满足 |
| INFO | F-004 | spec FR-007 | INVALID_DELETION_CODE 与 phone-sms-auth INVALID_CREDENTIALS 是不同 problem.type | 已设计正确 — T12 验证 problem.type 区分但 401 status 一致 |

**无 CRITICAL / HIGH** finding。

## CRITICAL / HIGH 修正项汇总

无。

## 建议下一步

1. **本 docs PR merge 后**：开 `feat(account): impl delete-account (M1.3 / T0-T13)` 进 implementation phase（per Phasing PR 拆分 PR 2）
2. **Implementation 前置确认**：1.4 logout-all + account-profile 已 ship（提供 `RefreshTokenRepository.revokeAllForAccount` + `JwtAuthFilter` status check）— ✅ 当前 main 已含
3. **PRD § 5.5 同步修订**：本 spec CL-001 落定 SMS only 后，应单独开 meta repo PR 修 PRD line 399 "密码 + 短信验证码" → "短信验证码"（reference ADR-0016），不阻塞本 spec
4. **配套 use case**：cancel-deletion impl PR 必须等 delete-account impl merge 后再起（提供 V7 + V8 + AccountSmsCodePurpose enum 物理结构）

## 与 cancel-deletion 的协同

| 协同点 | 描述 |
|---|---|
| State machine 配对 | delete-account markFrozen ↔ cancel-deletion markActiveFromFrozen，invariant：FROZEN ↔ ACTIVE 双向严格守 freeze_until > now 条件 |
| Schema 共享 | V7 freeze_until + V8 purpose 列由 delete-account 落地，cancel-deletion 复用零改动 |
| Enum 共享 | `AccountSmsCodePurpose` enum 在 delete-account ship `DELETE_ACCOUNT`，cancel-deletion 同 enum 加 `CANCEL_DELETION` |
| Event 配对 | delete: AccountDeletionRequestedEvent；cancel: AccountDeletionCancelledEvent — 两事件订阅方应成对 listener 对称设计（M2+ pkm 模块订阅时一并实现） |
| 测试 cross-spec | T12 `CrossUseCaseEnumerationDefenseIT` 同时覆盖 delete-account INVALID_DELETION_CODE + cancel-deletion INVALID_CREDENTIALS 字节级断言 |

cancel-deletion 单独 analysis 见 [`../cancel-deletion/analysis.md`](../cancel-deletion/analysis.md)。
