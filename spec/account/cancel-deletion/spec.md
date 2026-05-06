# Feature Specification: Cancel Deletion（撤销注销 — FROZEN → ACTIVE）

**Feature Branch**: `docs/account-cancel-deletion`
**Created**: 2026-05-06
**Status**: Draft（pending impl，docs-only PR；M1.3 roadmap）
**Module**: `mbw-account`
**Input**: User description: "用户在 15 天冷静期内想恢复账号 → 通过独立入口（不复用登录流）输入 phone + 新发的 SMS code → 账号转回 ACTIVE 并发 token 自动登录。"

> **Context**：[PRD § 5.5](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#55-账号注销) 定义 FROZEN 期内可撤销并恢复 ACTIVE。本 spec 覆盖 **FROZEN → ACTIVE 撤销路径**，是 [`../delete-account/spec.md`](../delete-account/spec.md) 的反向操作。
>
> **架构决策**：per `delete-account/spec.md` CL-002，撤销走 **dedicated `/api/v1/auth/cancel-deletion`** endpoint，**不复用 phone-sms-auth**（保 ADR-0016 反枚举不变性）。
>
> **状态机**：见 [`../account-state-machine.md`](../account-state-machine.md) — FROZEN 期内 cancel transition；ANONYMIZED 终态不可逆（cancel 必返 401）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 用户在冷静期内撤销注销并自动登录（Priority: P1）

FROZEN 账号用户在 15 天内想恢复 → 设置/登录页选「撤销注销」→ 输入手机号 → 收到 SMS code → 提交 code → 账号转 ACTIVE + 服务端发 token 直接登录到 home。

**Why this priority**: 主路径，所有撤销请求必经；用户从 FROZEN 回 ACTIVE 的唯一合法入口。

**Independent Test**: Testcontainers PG + Redis；预设 FROZEN 账号 +8613800138000，freeze_until=now+10d；

1. POST `/api/v1/auth/cancel-deletion/sms-codes` `{phone}` → 200（含 mock SMS 收到 6 位 code，purpose=CANCEL_DELETION）；
2. POST `/api/v1/auth/cancel-deletion` `{phone, code}` → 200 + `{accountId, accessToken, refreshToken}`；
3. DB account.status='ACTIVE' + freeze_until=NULL；
4. DB account.account_sms_code 该 row used_at != NULL；
5. 持新 access token 调 GET `/me` → 200 + 账号资料。

**Acceptance Scenarios**:

1. **Given** FROZEN 账号 +8613800138000 freeze_until 未到期，**When** POST `/auth/cancel-deletion/sms-codes` `{phone}`，**Then** 200 + mock SMS 收到 code；DB 写 `account.account_sms_code{purpose=CANCEL_DELETION, account_id, code_hash, expires_at=now+10min}`
2. **Given** 续 1 拿到 code，**When** POST `/auth/cancel-deletion` `{phone, code}`，**Then** 200 + 完整 token pair（与 phone-sms-auth 成功响应字节级一致 schema，含 accountId / accessToken / refreshToken）；DB account.status='ACTIVE' + freeze_until=NULL
3. **Given** 续 2，**When** 持新 access token 调 GET `/me`，**Then** 200（account-profile FR-009 status check 通过）
4. **Given** 续 2，**When** 调 GET `/me` 用旧 deletion 触发前残留的 access token（罕见，已 revoke 应取不到），**Then** 401（旧 token 已在 delete-account transition 时全部 revoke）

---

### User Story 2 - 反枚举：phone 不存在 / phone 存在但 ACTIVE / phone 存在但 ANONYMIZED（Priority: P1，并列）

撤销注销端点必须不暴露"该 phone 是否处于 FROZEN" — 三种非法情况返回相同的 sms-codes 200 响应（不实际发短信）+ 后续 cancel-deletion 同 401。

**Why this priority**: 安全；反枚举是 cancel-deletion 设计的核心约束（per CL-002）。

**Independent Test**: 预设 4 类 phone：[未注册 / ACTIVE 账号 / FROZEN 账号 / ANONYMIZED 账号]。

- 4 类 phone 调 sms-codes 端 → 全部 200，但仅 FROZEN 真发短信，其他 3 类不发；客户端无法通过响应区分；
- 4 类 phone 任意 6 位 code 调 cancel-deletion → 仅 "FROZEN + 正确 code" 返 200 + token，其他全 401 INVALID_CREDENTIALS（字节级一致）。

**Acceptance Scenarios**:

1. **Given** phone +8613900139999 从未注册，**When** POST `/auth/cancel-deletion/sms-codes` `{phone}`，**Then** 200（与 FROZEN 命中字节级一致），但 mock SMS provider 未收到调用（短信不真发）
2. **Given** phone 关联 ACTIVE 账号（如 +8613800138001），**When** POST `/auth/cancel-deletion/sms-codes`，**Then** 200（同上反枚举），mock 未收到
3. **Given** phone 关联 ANONYMIZED 账号，**When** POST `/auth/cancel-deletion/sms-codes`，**Then** 200（per `account-state-machine.md`，ANONYMIZED account 的 phone 已置 NULL，故视为"未注册"），mock 未收到
4. **Given** 上述 3 种 phone 任意一种 + 任意 6 位 code，**When** POST `/auth/cancel-deletion` `{phone, code}`，**Then** 401 INVALID_CREDENTIALS（与 FROZEN+错码 字节级一致 + 时延差 ≤ 50ms）

---

### User Story 3 - SMS code 错误 / 过期 / 已用（Priority: P1，并列）

二次验证 code 必须有限次尝试 + 过期 + 单次使用。

**Why this priority**: 撤销 transition 是不可重复操作，二次验证必须严格。

**Independent Test**: 续 User Story 1 拿到 code 后：

- 提交错误 6 位数字 → 401 INVALID_CREDENTIALS；
- 等 10min+ 后提交正确 code → 401（已过期，吞）；
- 同 code 连续提交 2 次：第 1 次 200（成功 → ACTIVE），第 2 次 401（已用 + 账号已 ACTIVE，吞）

**Acceptance Scenarios**:

1. **Given** FROZEN 账号 + 持有正确 phone + 错误 6 位 code，**When** POST `/auth/cancel-deletion`，**Then** 401 INVALID_CREDENTIALS；DB account.status 不变（仍 FROZEN）
2. **Given** code 已过期（10min+），**When** POST `/auth/cancel-deletion`，**Then** 401（与"错码"字节级一致）
3. **Given** 同 code 5 次错误尝试，**When** 第 6 次再尝试任意 code，**Then** 429 + RATE_LIMITED + Retry-After
4. **Given** code 已用（之前 transition ACTIVE 成功），**When** 再次提交同 code，**Then** 401（用户当前已 ACTIVE 账号 → 复用 phone-sms-auth FR-005 ACTIVE 路径但 cancel-deletion 仅认 FROZEN→ACTIVE，故仍 401）

---

### User Story 4 - freeze_until 已过期：cancel 失败（Priority: P1，并列）

PRD § 5.5 line 410-413 — freeze_until < now 时定时任务已或将匿名化账号；此时 cancel 必败（即使账号尚未实际 anonymize）。

**Why this priority**: 状态机不变量保护；scheduler-driven anonymize 与 cancel 同期可能产生竞态。

**Independent Test**: 预设 FROZEN 账号 freeze_until=now-1min（人工设过期）→ 走完整 cancel 流程提交正确 code → 401。

**Acceptance Scenarios**:

1. **Given** FROZEN 账号 freeze_until = now - 1 second（grace period 已结束），**When** POST `/auth/cancel-deletion/sms-codes` `{phone}`，**Then** 200（反枚举），但内部不发短信 + DB 不写 sms_code 行（视为已不可撤销）
2. **Given** 续 1，**When** 用任何 6 位 code 调 `/auth/cancel-deletion`，**Then** 401 INVALID_CREDENTIALS（与 FROZEN 期内 + 错码 字节级一致）
3. **Given** ANONYMIZED 账号（scheduler 已跑完 anonymize），**When** 同上流程，**Then** 全部 401

---

### Edge Cases

- **多设备同时撤销**：A 设备成功 transition ACTIVE 后，B 设备提交同 code → 401（账号已 ACTIVE，反枚举）
- **同 phone 60s 内连发 sms-codes**：第 2 次 429（IP/account 维度限流，60s 1 次）— 反枚举：未注册 phone 也走同一限流键
- **transition 后 cancel 再 delete 再 cancel**：合规 — 每次 cancel 后账号回 ACTIVE 可再触发新 deletion 流程，不限次数（仅受频率限流）
- **真发短信失败**（FROZEN + sms provider 5xx）：返回 503（不反枚举吞，因为攻击者无 phone 也得 503，无信息差），但 attacker 通过 503 vs 200 仍可推测 phone 是否 FROZEN — **接受小信息泄露**，参 Clarifications CL-001
- **POST body 缺 phone / code 字段**：`VALIDATION_FAILED` 400（Jakarta `@NotBlank`）
- **POST body phone 格式错（非 E.164）**：400 VALIDATION_FAILED（与 phone-sms-auth FR-002 同 pattern）
- **freeze_until 字段缺失（旧 FROZEN 账号 V7 之前的）**：M1.3 时点尚无生产用户，本 case 不可达；ITs 不覆盖

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001（发码 endpoint）**：唯一 endpoint `POST /api/v1/auth/cancel-deletion/sms-codes` `{phone}`；响应统一 200 No Content（反枚举）
- **FR-002（撤销 endpoint）**：唯一 endpoint `POST /api/v1/auth/cancel-deletion` `{phone, code}`；响应：
  - 200 + `LoginResponse {accountId, accessToken, refreshToken}` 仅在"phone 关联 FROZEN 账号 + freeze_until 未过期 + code 正确"时返回
  - 401 INVALID_CREDENTIALS 其他所有失败路径（与 phone-sms-auth INVALID_CREDENTIALS schema 字节级一致）
  - 429 RATE_LIMITED 限流
  - 400 VALIDATION_FAILED schema 错
- **FR-003（无鉴权）**：两端点均**不**要求 Bearer token（用户处于 FROZEN 已 logged out，无 token）；与 phone-sms-auth 同；服务端通过 phone + code 自验证身份
- **FR-004（SMS code 持久化）**：复用 `account.account_sms_code` 表 + `purpose=CANCEL_DELETION`（per delete-account CL-005）；6 位数字 code SHA-256 hex；expires_at = now + 10min
- **FR-005（限流）**：
  - 发码：phone 维度 60s 1 次（防短信费用滥发）；IP 维度 60s 5 次
  - 提交：phone 维度 60s 5 次；code 维度 5 次（attempts 总和）；IP 维度 60s 10 次
  - 超限 → 429 ProblemDetail + `Retry-After`
  - **限流键统一基于 phone hash**，对未注册 / FROZEN / ACTIVE / ANONYMIZED 4 类一视同仁，不暴露分类
- **FR-006（ACTIVE transition 原子性）**：撤销必须在单一 `@Transactional(rollbackFor = Throwable.class)` 内完成：
  1. 校验 code 通过 + 未过期 + 未用
  2. findById account → 校验 `status == FROZEN && freeze_until > now`（防 scheduler 抢跑）
  3. 标 code 已用
  4. account.markActive(now) — 转 ACTIVE + freeze_until=NULL（domain layer 行为）
  5. publish `AccountDeletionCancelledEvent` (新事件) 到 outbox
  6. issue access + refresh token via `TokenIssuer`（与 phone-sms-auth FR-005 同）
  7. 持久化新 refresh token 到 `account.refresh_token`（per refresh-token FR-009）
  - 任一步失败 → 全部回滚；status 仍 FROZEN
- **FR-007（错误响应格式）**：所有错误遵循 RFC 9457 ProblemDetail（`application/problem+json`）；错误码：
  - `INVALID_CREDENTIALS` 401（phone 未关联 FROZEN / code 错 / code 过期 / code 已用 / freeze_until 已过期 — 字节级一致，反枚举）
  - `RATE_LIMITED` 429
  - `VALIDATION_FAILED` 400
  - `SMS_SEND_FAILED` 503（仅 FROZEN 命中且 SMS 真发失败时）
- **FR-008（事件）**：cancel transition 成功路径**必发** `AccountDeletionCancelledEvent`：
  - Payload：`(AccountId accountId, Instant cancelledAt, Instant occurredAt)`
  - Outbox：Spring Modulith Event Publication Registry
  - 当前消费方：本期无；为 M2+ pkm 等订阅 `AccountDeletionRequestedEvent` 的模块预留 unwind 信号
- **FR-009（OpenAPI 暴露）**：Springdoc 自动从 controller 推导 schema
- **FR-010（不修改 phone-sms-auth FROZEN 行为）**：FROZEN 账号通过 phone-sms-auth 登录仍按 phone-sms-auth FR-005 反枚举吞；本 spec 通过独立 endpoint 提供撤销路径，不改动 phone-sms-auth
- **FR-011（埋点）**：cancel transition 成功后埋点 `account_delete_cancel` + `days_remaining`（per PRD § 5.9）；本 spec 仅声明事件名，埋点接入由埋点模块统一处理（M2+；本期 commented `// TODO`）

### Key Entities

- **Account（聚合根）**：扩展既有 Account
  - 复用 `markActive(Instant now)` 行为（既有，per phone-sms-auth）；本 spec 不新增 domain 行为
  - cancel transition 调 `markActive` 时同时清空 `freezeUntil` — 需扩展 `markActive` 签名或新增 `markActiveFromFrozen(Instant now)` 行为
- **AccountStateMachine（domain service）**：扩展既有 service
  - 新增 `markActiveFromFrozen(Account, Instant now)`：校验 status==FROZEN && freeze_until > now → 转 ACTIVE + 清 freeze_until；非法 → `IllegalAccountStateException`
- **AccountSmsCodePurpose（既有 enum，扩展）**：加 `CANCEL_DELETION` 值（与 delete-account 同期扩展同 enum）
- **AccountDeletionCancelledEvent（domain event，新增）**：record `(AccountId accountId, Instant cancelledAt, Instant occurredAt)`，放 `mbw-account.api.event` 包
- **删除**：无

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**：发码 P95 ≤ 1.5s（命中 FROZEN 含真实 SMS 发送，per L2 档）；非 FROZEN 不发短信 P95 ≤ 200ms（仅查 DB + 写限流）
- **SC-002**：撤销 transition P95 ≤ 300ms（含 DB write + outbox + token issue + refresh_token persist，per L1 档）
- **SC-003**：ACTIVE transition **原子性** — IT 模拟 "FR-006 第 7 步 refresh_token persist 抛异常" → 全部回滚（status 仍 FROZEN + sms_code.used_at 仍 null + outbox 无新事件）
- **SC-004**：反枚举 — IT 验 4 类 phone 调 sms-codes 后端 200 + 时延差 ≤ 100ms；4 类失败 cancel 401 字节级一致 + 时延差 ≤ 50ms
- **SC-005**：限流准确性 — FR-005 五条规则集成测试覆盖；429 + 正确 `Retry-After`
- **SC-006**：状态机不变量 — IT 验 FROZEN → ACTIVE 后 freeze_until 清空；同时验 ANONYMIZED → ACTIVE 路径必败（终态不可逆）
- **SC-007**：与 phone-sms-auth 字节级解耦 — phone-sms-auth 响应 schema 零改动；cancel-deletion 200 响应 = phone-sms-auth 成功响应字节级一致 schema（同 LoginResponse），但 401 路径独立 problem.type
- **SC-008**：scheduler 抢跑场景 — IT 模拟 freeze_until 在 cancel sms-codes 与 cancel submit 之间过期 → submit 必返 401（不允许抢救已过期的撤销）
- **SC-009**：ArchUnit / Spring Modulith Verifier CI 仍 0 violation
- **SC-010**：OpenAPI snapshot 含两 endpoint + 错误响应描述

## Clarifications

> 1 题待 user 在 `/speckit.clarify` 阶段确认。其余 clarifications 已在 delete-account spec CL-001~CL-005 解决并复用。

### CL-001：sms-codes 失败响应（SMS 服务挂）是否暴露 phone 是否 FROZEN

**Q**：FR-005 + Edge Cases — 命中 FROZEN 时真发短信失败 → 503；未命中 FROZEN 不发短信 → 200。攻击者通过响应码 200 vs 503 可推测 phone 是否处于 FROZEN（信息泄露）。是否需要进一步统一？

**推荐**：**接受小信息泄露**。理由：

- (1) 完美反枚举 = 不发短信 = cancel-deletion 完全失效（FROZEN 用户也收不到 code）— 不可接受
- (2) 替代方案"发完才报错"会让 SMS provider 真扣费 — 经济不合理
- (3) 信息泄露窗口 ≤ 15 天（FROZEN grace period）+ 限流（同 phone 60s 1 次）+ 错误代码描述模糊化（"系统繁忙，请稍后再试"，不暴露 SMS 字样）
- (4) 攻击者已可通过 sms-codes 响应时延（FROZEN 命中含 SMS 调用 ~1s vs 不命中 ~200ms）部分推测 — 信息差小

**反方观点**：完全字节一致 + 永远 200 — 但 SMS 服务挂时用户被静默欺骗（以为 code 在路上其实没发）— UX 灾难。

**落点**：FR-007 显式声明 SMS_SEND_FAILED 503 仅 FROZEN 命中时返回；SC-004 时延差容忍 ≤ 100ms；客户端文案"系统繁忙"模糊化；FR-005 严格限流。

## Assumptions

- **A-001**：复用既有 `RateLimitService` / `JwtTokenIssuer` / `AccountRepository` / `RefreshTokenRepository` / `AccountSmsCodeRepository` / `SmsClient`
- **A-002**：复用 delete-account 同期落地的 `AccountSmsCodePurpose` enum + V8 migration（purpose 列）；本 spec 不重复定义
- **A-003**：复用 delete-account 同期落地的 `account.freeze_until` 列 + V7 migration；本 spec 不重复定义
- **A-004**：cancel transition 后发新 token，复用 phone-sms-auth 既有 `LoginResponse` schema + token issuance 路径（per refresh-token FR-009 也持久化新 RefreshTokenRecord）
- **A-005**：M1.3 时点 anonymize scheduler 尚未上线，FR-006 第 2 步 freeze_until 校验仅防御未来；M1.3 之后 scheduler 与 cancel 之间的 race 由 SC-008 测试覆盖
- **A-006**：本 spec 与 delete-account spec 在同一双 PR 周期 ship；T-T 顺序上 delete-account migration（V7 + V8）必先 merge

## Out of Scope

- **scheduler-driven anonymize 行为** — 单独 spec `spec/account/anonymize-frozen-accounts/`（M1.3 后续）
- **delete-account 入口 / FROZEN transition** — [`../delete-account/spec.md`](../delete-account/spec.md)
- **PRD § 5.5 文本修订** — 单独 PR
- **埋点接入** — M2+ 埋点模块；本 spec FR-011 仅声明事件名
- **审计日志**（"何时何 IP 触发撤销"） — 由 `LoginAudit` 统一记录
- **撤销后 client 自动同步本地存储**（如清空"待撤销提醒" UI 状态） — 客户端逻辑，server 仅返新 token
- **批量行政撤销**（admin 主动恢复违规账号） — 不在本期；admin 模块引入后单独 spec

## References

- [`../delete-account/spec.md`](../delete-account/spec.md) — 配套（反向）use case
- [`../account-state-machine.md`](../account-state-machine.md) — 状态定义 + invariants
- [`../phone-sms-auth/spec.md`](../phone-sms-auth/spec.md) — SMS code 流程 + 反枚举 pattern + LoginResponse schema
- [`../../auth/refresh-token/spec.md`](../../auth/refresh-token/spec.md) — refresh_token 持久化（cancel 也需）
- [PRD § 5.5 账号注销](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#55-账号注销)
- [ADR-0016](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md) — 不变量：phone-sms-auth FR-006 反枚举不可破
