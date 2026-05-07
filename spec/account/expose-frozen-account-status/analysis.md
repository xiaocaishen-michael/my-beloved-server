# Analysis: Expose Frozen Account Status

**Spec**: [`./spec.md`](./spec.md) · **Plan**: [`./plan.md`](./plan.md) · **Tasks**: [`./tasks.md`](./tasks.md)
**Created**: 2026-05-07（`/speckit.analyze` round 1）

> 跨 spec / plan / tasks / phone-sms-auth amendment 计划 / `.specify/memory/constitution.md` 一致性扫描。catch 矛盾、覆盖盲区、违宪、Anti-Patterns。

## 1. FR Coverage 矩阵（spec.md → plan.md → tasks.md）

| FR | spec.md | plan.md | tasks.md | 验证 |
|---|---|---|---|---|
| FR-001 `AccountInFreezePeriodException` 新建 | ✅ Functional Requirements | ✅ 架构层级 + Domain Exception 段 | ✅ T1 (test + impl) | 完整 |
| FR-002 use case 拆 FROZEN/ANONYMIZED 分支 | ✅ | ✅ 核心 use case 流程段 + 改动 diff | ✅ T3.2 | 完整 |
| FR-003 web advice handler | ✅ | ✅ 核心 use case 流程段 § Web Advice | ✅ T4 | 完整 |
| FR-004 timing defense bypass | ✅ | ✅ TimingDefenseExecutor 改动段 + 关键技术决策 CL-003 | ✅ T2 + T3.1 | 完整 |
| FR-005 限流契约不变（FROZEN 不计入失败计数） | ✅ | ✅ 不动文件清单（隐式） | ⚠️ **无 explicit verify task**（依赖 T5 既有 IT 隐式覆盖）| 见 Gap 1 |
| FR-006 SMS code 消费时机不变 | ✅ | ✅ 关键技术决策 CL-002 | ⚠️ 同上 | 见 Gap 1 |
| FR-007 错误码归属（mbw-account.domain.exception 内部常量） | ✅ | ✅ 不引入 mbw-shared 新增 | ✅ T1.impl `CODE` 常量定义 | 完整 |
| FR-008 同 PR 修订 phone-sms-auth/spec.md | ✅ | ✅ S0 stage | ✅ T0 (T0.1-T0.5) | 完整 |
| FR-009 OpenAPI Springdoc 自动同步 | ✅ | ✅ 实现 stage S7 | ✅ T7.1 | 完整 |
| FR-010 不引入新 endpoint / migration / 跨模块 | ✅ | ✅ 不动文件清单 + Out of Scope | ✅ implicit（无对应改动 task）| 完整 |

## 2. SC Coverage 矩阵（spec.md → tasks.md）

| SC | spec.md | tasks.md | 验证机制 |
|---|---|---|---|
| SC-001 `FrozenAccountStatusDisclosureIT` 新建 | ✅ | ✅ T6 | Testcontainers + assertj |
| SC-002 `SingleEndpointEnumerationDefenseIT` 修订 3 路径 | ✅ | ✅ T5 | Testcontainers + 1000 次循环 |
| SC-003 `UnifiedPhoneSmsAuthUseCaseTest` 续绿 + 新增 case | ✅ | ✅ T3-test | mvn test |
| SC-004 OpenAPI spec 含 ACCOUNT_IN_FREEZE_PERIOD | ✅ | ✅ T7.1 | curl + jq |
| SC-005 phone-sms-auth/spec.md 同 PR 修订完成 | ✅ | ✅ T0 + T8.1 git diff | git diff review |
| SC-006 既有 phone-sms-auth IT suite 全绿 | ✅ | ✅ T8.1 mvn verify (implicit) | mvn verify |
| SC-007 真后端冒烟 | ✅ | ✅ T7.2 | 手动 + 截图 |
| SC-008 改动 ≤ 200 LOC main src | ✅ | ✅ T8.1 git diff --stat | git diff |
| SC-009 ArchUnit + Spring Modulith Verifier 0 violation | ✅ | ✅ T1 verify + T8.1 ModuleStructureTest | ArchUnit |
| SC-010 前端 SDK 同步 readiness | ✅ | ✅ T8.4 post-merge 通知 spec C | 后续验证 |

## 3. Clarification Coverage 矩阵（spec.md Clarifications → plan.md / tasks.md）

| CL | spec.md | plan.md 落点 | tasks.md 落点 |
|---|---|---|---|
| CL-001 暴露 freezeUntil ISO 8601 | ✅ Clarifications | ✅ 关键技术决策 CL-001 行 + Domain Exception getter + Web Advice setProperty | ✅ T1.impl getter + T4 setProperty |
| CL-002 SMS code 消费顺序不变 | ✅ | ✅ 关键技术决策 CL-002 行 + 数据流第 3 步 + 不动文件清单注释 | implicit（不引入新 task）|
| CL-003 TimingDefenseExecutor 加 bypassPad 参数 | ✅ | ✅ TimingDefenseExecutor 改动段（含 boolean shouldPad flag pattern）+ 关键技术决策 CL-003 行 | ✅ T2.impl |
| CL-004 修订既有 SingleEndpointEnumerationDefenseIT | ✅ | ✅ 关键技术决策 CL-004 行 + 测试策略一览 | ✅ T5 |
| CL-005 javadoc 标 disclosure intent | ✅ | ✅ Domain Exception 段 javadoc 完整内容 + 关键技术决策 CL-005 行 | ✅ T1.impl 必带 javadoc |

## 4. Constitution Compliance 检查（`.specify/memory/constitution.md`）

| 原则 | 检查 | 结果 |
|---|---|---|
| I. Modular Monolith (NON-NEGOTIABLE) | spec D 改动是否破坏模块边界 | ✅ 仅动 mbw-account 内部；零 mbw-shared 改动；零跨模块 import |
| II. DDD 5-Layer (NON-NEGOTIABLE) | AccountInFreezePeriodException 是否零 framework 依赖 | ✅ T1.impl 明示 `import` 段无 `org.springframework.*` / `jakarta.persistence.*`；ArchUnit 兜底（SC-009）|
| III. TDD Strict (NON-NEGOTIABLE) | 每条 [Domain] / [Application] task 是否红→绿 | ✅ T1 / T2 / T3 都 T-test → T-impl 拆分；T4（web advice）走 TDD 例外（per CLAUDE.md § 一 纯 advice handler 由 IT 覆盖）|
| IV. SDD via Spec-Kit Four-Step | spec.md 是否严格 3 段不自创子层 | ✅ User Scenarios & Testing / Functional Requirements / Success Criteria 三主段；Clarifications / Assumptions / Out of Scope / Open Questions / References / 变更记录是 spec-kit 标准元信息（与 phone-sms-auth / cancel-deletion / anonymize-frozen-accounts 既有 spec 一致），不是自创子层 |
| V. DB Schema Isolation | 是否引入 cross-schema FK / 跨 schema 改动 | ✅ FR-010 零 migration / 零 schema 改动 |

## 5. Anti-Patterns 检查（constitution § Anti-Patterns）

| Anti-Pattern | 检查 | 结果 |
|---|---|---|
| Cross-module direct dependency | spec D 是否引入业务模块直接依赖 | ✅ 无 |
| Domain model annotated with @Entity / @Column / @JsonIgnore | AccountInFreezePeriodException 是否注解 framework | ✅ 无 |
| Cross-schema foreign keys | spec D 是否新建 FK | ✅ 无（无 migration）|
| Skipping TDD red-green | 是否有 task 跳 TDD | ✅ T1/T2/T3 严格红绿；T4 走文档化 TDD 例外 |
| spec.md duplicating OpenAPI data contracts | spec.md FR-003 ProblemDetail body 字段定义是否算 OpenAPI schema | ⚠️ **边界 case** — 见 Gap 2 |
| tasks.md finer than 30min-2h work units | T0-T8 颗粒度是否合理 | ✅ T0 ~30min(5 sub-edits) / T1 ~30min / T2 ~1h / T3 ~1.5h / T4 ~30min / T5 ~30min / T6 ~1h / T7 ~30min / T8 ~30min |
| Spec drift > 1 week | phone-sms-auth/spec.md 是否同 PR 修订 | ✅ FR-008 + T0 强制同 PR；防 drift > 1 week |
| Self-creating spec sub-layers | spec.md 是否在 3 主段下自创子段 | ✅ User Scenarios 段下用 spec-kit 标准 User Story # / Why / Independent Test / Acceptance Scenarios / Edge Cases；Functional Requirements 段单层 table；Success Criteria 段单层 table — 无自创子层 |

## 6. Gaps & Findings

### Gap 1（Minor，non-critical，impl-time 可补）— FR-005 / FR-006 行为不变约束无 explicit verify task

**问题**：spec.md FR-005（限流契约不变 — FROZEN 不计入失败计数）+ FR-006（SMS code 消费时机不变）是 "行为不变" 约束；tasks.md 没有 explicit verify task 覆盖此约束。当前依赖 T5 `SingleEndpointEnumerationDefenseIT` 修订后跑限流路径 + SMS code 消费路径**隐式**验证。

**风险**：T5 IT 修订时若误删限流 / SMS code 行为相关断言（专注于 FROZEN 移除），可能让 FR-005 / FR-006 行为悄悄退化。

**建议补救（impl 阶段）**：

- option A — T5 impl 时显式保留 IT 中限流 case + SMS code 消费 case 的断言
- option B — 在 T6 `FrozenAccountStatusDisclosureIT` 加一条额外 assert：100 次循环过程中 `auth:<phone>` rate-limit bucket 计数应保持为 0（FROZEN 路径不计入；可通过查 Redis 验证）
- option C — 显式新建一条 task T9 `[Verify] FR-005/FR-006 不变性` — 但成本高于 option A/B

**推荐**：option A（最低成本）+ option B（额外保险）。impl 阶段 T5 / T6 的 PR review 时 catch。

### Gap 2（Minor，争议低）— spec.md FR-003 ProblemDetail body 字段定义是否算 OpenAPI data contract duplication

**问题**：constitution Anti-Pattern "spec.md duplicating OpenAPI data contracts" 要求 spec.md 不重复 OpenAPI schema 定义。spec.md FR-003 含字段名 `code` + `freezeUntil` + 类型描述（ISO 8601 UTC string）— 边界 case：是 web 行为契约还是 OpenAPI data contract？

**判定**：✅ **不算违反**。理由：

- spec.md FR-003 描述的是 web advice handler 行为（"setProperty('code', ...)" 是 use case behavior，不是 OpenAPI schema）
- OpenAPI schema 由 Springdoc 从 `@ExceptionHandler` 自动反射（per FR-009），spec.md 不手写 OpenAPI annotation
- spec C `delete-account-cancel-deletion-ui/spec.md` 同款 pattern（FR-010 描述 mapApiError switch case `ACCOUNT_IN_FREEZE_PERIOD → ...`）— 业内 SDD 共识：行为契约描述不算 OpenAPI schema duplication

**建议**：✅ 保持现状；plan.md 已明示 OpenAPI 由 Springdoc 自动反射（per CL-003 实施细节段）。

### Gap 3（Minor，non-critical，impl-time 可补）— spec.md Edge Cases 有未决 sub-decision

**问题**：spec.md Edge Cases 段第 2 条 "FROZEN 账号 freeze_until 字段为 NULL（数据异常，理论不可能）：fall through 到 ANONYMIZED 分支处理 / 仍走 FROZEN 路径（plan.md 决，建议保守 fallback 到 401 INVALID_CREDENTIALS 反枚举吞，避免暴露异常 status）" — plan.md 没明确决议这个 edge case。

**风险**：极低（FR-010 V7 migration 已强制 freeze_until 非 NULL when status=FROZEN，per `delete-account/spec.md` + `anonymize-frozen-accounts/plan.md` 状态机不变量；理论数据异常场景）。

**建议补救（impl 阶段）**：T3.2 实现 FROZEN 分支时显式判：

```java
if (account.status() == AccountStatus.FROZEN) {
    Instant freezeUntil = account.freezeUntil();
    if (freezeUntil == null) {
        // Data anomaly: FROZEN account without freeze_until.
        // Fallback to anti-enumeration to avoid disclosing anomalous state.
        throw new InvalidCredentialsException();
    }
    throw new AccountInFreezePeriodException(freezeUntil);
}
```

T3-test 加一 case：`FROZEN account with null freezeUntil → fallback to InvalidCredentialsException`（保守反枚举吞）。

### Gap 4（Minor，non-critical，clarification 可补）— tasks.md T3-test 表述精度

**问题**：T3-test 表述 "既有 8 case 续绿" 不精确 — 实际是：

- 7 case 续绿（Happy ACTIVE / Happy 未注册自动创建 / ANONYMIZED 反枚举 / 验证码错 / 限流触发 / Phone 格式错 / 并发同号自动注册）
- 1 case **改动行为**（FROZEN 反枚举 → FROZEN disclosure）
- 3 case **新增**（FROZEN bypass timing pad / ANONYMIZED 仍 timing pad / ACTIVE 成功仍 timing pad）

**风险**：低；impl 时 grep 既有 `UnifiedPhoneSmsAuthUseCaseTest.java` 可对照。

**建议补救（impl 阶段）**：T3-test 改 description 表述精确 + 在 commit message 注明 case 变更分布。

### Gap 5（Note，maintainer 决策）— OpenAPI breaking change commit semantics

**问题**：spec D 改 phone-sms-auth 响应（FROZEN 路径 401 → 403）— 是 OpenAPI breaking change（前端旧 generated client 会把 403 当未知错误）。Conventional Commits 规范应用 `feat!:` 或 body 加 `BREAKING CHANGE:` footer 触发 release-please major bump。

**判定**：M1 v0.x.x 阶段（per phone-sms-auth CL-002 + meta CLAUDE.md "M1 v0.x.x 无真实用户"），semver 规范 0.x 不严格触发 major（0.x.x 整个阶段都视为不稳定）。是否标 BREAKING CHANGE 由 maintainer 决：

- option A — 不标（M1 v0.x.x 都不稳定）：commit `feat(account): expose-frozen-account-status (M1.X / spec C 前置)` 走 minor bump
- option B — 标（语义透明）：commit body 加 `BREAKING CHANGE: phone-sms-auth FROZEN path response changed from 401 INVALID_CREDENTIALS to 403 ACCOUNT_IN_FREEZE_PERIOD; consumers (notably no-vain-years-app spec C) must regenerate API client.` 触发 major bump

**推荐**：option B（语义透明 + 给 release-please CHANGELOG 写出 breaking 提示，对未来 M3+ 真实用户 onboard 时回看历史更友好）；M1 v0.x.x 即使触发 major（0.x → 0.(x+1)）也无副作用。

**落点**：建议 T8.2 commit body 加 BREAKING CHANGE footer。

## 7. Cross-spec Coherence 检查

### vs phone-sms-auth/spec.md（同 PR 修订对象）

| 检查项 | 结果 |
|---|---|
| spec D FR-008 列出的 phone-sms-auth amendment 5 子项是否齐全 | ✅ FR-005 / FR-006 / SC-003 / Clarifications CL-006 / 变更记录 — 5 条都覆盖 |
| spec D 对 phone-sms-auth FR-006 的范围描述是否一致 | ✅ 都说 "缩为 ANONYMIZED + 码错 + 未注册自动创建路径" |
| spec D 对 phone-sms-auth SC-003 路径数从 4 改 3 是否一致 | ✅ 都说 "ACTIVE 成功 / 未注册自动注册成功 / ANONYMIZED + 码错共反枚举吞" |
| 是否会引发 phone-sms-auth 其他 FR / SC drift | ✅ 不会 — spec D 不动 phone-sms-auth FR-001 / FR-002 / FR-003 / FR-004 / FR-007 / FR-008 / FR-009 / FR-010 / FR-011 / FR-012；不动 SC-001 / SC-002 / SC-004 ~ SC-007 |

### vs spec C `delete-account-cancel-deletion-ui/spec.md`（下游消费方）

| 检查项 | 结果 |
|---|---|
| spec D 实施的契约是否满足 spec C spec.md L13-14 假设 | ✅ spec C 假设 "phone-sms-auth 检测 status==FROZEN 时返 HTTP 错误响应含错误码 ACCOUNT_IN_FREEZE_PERIOD（403 / 401-with-code 字段均可，具体 spec D 决）" — spec D 决 403 + ProblemDetail body code 字段 |
| spec D 是否暴露 freezeUntil（spec C Q3 决议不消费天数）| ✅ spec D CL-001 暴露 freezeUntil；spec C 选择不消费 — 不冲突，spec D 给 PHASE 2 留扩展口 |
| spec D 改动是否影响 spec C 其他契约假设（cancel-deletion endpoint / accounts/me/deletion endpoint）| ✅ 不影响 — spec D Out of Scope 明示 cancel-deletion endpoint 已落地不动 |
| spec C 启动 impl session 的前置 | ✅ spec D ship 后才开 spec C impl（spec D plan.md 衔接边界段 + spec C spec.md L116 同款表述）|

### vs cancel-deletion / delete-account / anonymize-frozen-accounts（关联 use case）

| 检查项 | 结果 |
|---|---|
| spec D 是否动这 3 个 use case 的 use case / repo / domain | ✅ 不动 — plan.md "不动文件列表" 显式列出 |
| spec D 状态机理解（ACTIVE → FROZEN → ANONYMIZED）是否与 account-state-machine.md 一致 | ✅ spec D 仅消费 status 字段，不修改状态机 transition |

## 8. 收尾结论

**Phase 1 Doc 收尾 — 三件套 + analysis ready for impl**：

- spec.md / plan.md / tasks.md 跨文件一致性扫描通过
- 5 项 cross-cutting clarifications 全部决议
- Constitution 5 项 NON-NEGOTIABLE + 8 项 Anti-Patterns 全部满足（边界 case Gap 2 已判定不算违反）
- 5 项 Minor non-critical 缺漏（Gap 1 / 3 / 4 + Gap 5 maintainer 决策）均 impl-time 可补，不阻塞 Phase 1 Doc 收尾

**impl 启动前置**：

- 等用户 review spec.md / plan.md / tasks.md / analysis.md 4 文件 → GO 信号
- T0 同 PR amend phone-sms-auth/spec.md（防 spec drift）
- 进 `/speckit.implement` session（Phase 2 — server PR 落地 + auto-merge + spec C ship 信号）

**下游 readiness**：spec D PR ship 后立即可启 spec C `/speckit.implement` session（per spec C spec.md "Phase 2 `/speckit.implement` 必须等 server 仓 spec D ship 后才开"）。
