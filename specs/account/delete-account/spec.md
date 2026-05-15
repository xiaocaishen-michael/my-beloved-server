# Feature Specification: Delete Account（账号注销 — ACTIVE → FROZEN 入口）

**Feature Branch**: `docs/account-delete-account`
**Created**: 2026-05-06
**Status**: Draft（pending impl，docs-only PR；M1.3 roadmap）
**Module**: `mbw-account`
**Input**: User description: "用户在设置页发起账号注销 → 二次验证（SMS code）通过 → 账号转 FROZEN 进入 15 天冷静期；当前所有 session 立即失效。"

> **Context**：[PRD § 5.5](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#55-账号注销) 定义了完整生命周期 ACTIVE →(注销) FROZEN(15d) →(到期定时) ANONYMIZED；本 spec 仅覆盖 **ACTIVE → FROZEN 入口**（用户主动发起）。配套 use case：`cancel-deletion`（FROZEN → ACTIVE 撤销）+ scheduler-driven anonymize（FROZEN → ANONYMIZED）单独 spec。
>
> **状态机定义不重复声明**：见 [`../account-state-machine.md`](../account-state-machine.md) ACTIVE / FROZEN / ANONYMIZED + invariants。本 spec 仅引用。
>
> **二次验证 = SMS code only**：per [ADR-0016](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md) 决策 — password 已废，主登录 = phone+SMS；同 unbind 凭证流程（PRD § 5.3）二次验证统一走 SMS code，PRD § 5.5 早期版本 "密码 + 短信验证码 双重验证" 表述已废，需要 user 在 clarify 阶段确认（CL-001）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 用户主动发起注销：双步流程（Priority: P1）

用户在「设置 - 账号安全 - 注销账号」点击发起 → 服务端发短信验证码 → 用户提交验证码 → 账号转 FROZEN，所有 session 立即失效，客户端被强制登出至登录页。

**Why this priority**: 主路径，所有注销请求必经；状态机入口的唯一触发点。

**Independent Test**: Testcontainers PG + Redis；预设 ACTIVE 账号 +8613800138000 + 持有 access token；

1. POST `/api/v1/accounts/me/deletion-codes` Bearer auth → 200 + 服务端发码（mock SMS）；
2. POST `/api/v1/accounts/me/deletion` `{code}` Bearer auth → 204；
3. DB `account.account` row 该账号 status='FROZEN' + freeze_until = now() + 15d ± clock skew；
4. DB `account.refresh_token` 该账号 active rows 全部 revoked_at != NULL；
5. 同 access token 调任意 `/me` GET → 401（FROZEN 拦截，per account-profile FR-009）。

**Acceptance Scenarios**:

1. **Given** ACTIVE 账号 + 持有未过期 access token + 60s 内未发过 deletion code，**When** POST `/me/deletion-codes`，**Then** 200 + mock SMS 收到含 6 位数字 code；DB 写 `account_sms_code{purpose=DELETE_ACCOUNT, account_id, code_hash, expires_at=now+10min}`
2. **Given** 续 1 + 持有正确 code，**When** POST `/me/deletion` `{code}`，**Then** 204 No Content；DB account.status='FROZEN' + freeze_until 写入 + active refresh_token 全 revoke
3. **Given** 续 2，**When** 持原 access token 调 `GET /me`，**Then** 401 ProblemDetail（per account-profile FR-009 FROZEN 拒接 token）
4. **Given** 续 2，**When** 任意设备发起 `POST /api/v1/auth/refresh-token` 用旧 refresh token，**Then** 401 INVALID_CREDENTIALS（per refresh-token FR — revoked token = invalid，反枚举吞）

---

### User Story 2 - 鉴权失败：未持有 access token / 已 FROZEN（Priority: P1，并列）

未鉴权请求 / 已 FROZEN 账号请求 deletion 任一 endpoint 必须拒绝。

**Why this priority**: 鉴权是 ACTIVE → FROZEN transition 合法性的前置门，必须在 controller 入口拦截。

**Independent Test**:

- POST `/me/deletion-codes` 不带 Bearer → 401；
- 预设 FROZEN 账号 + 手工签 access token，POST `/me/deletion-codes` Bearer → 401（per account-profile FR-009 — FROZEN 不发 token，但 token TTL 内可能残留，filter 必查 status）。

**Acceptance Scenarios**:

1. **Given** 任意设备无 Authorization header，**When** POST `/me/deletion-codes`，**Then** 401 ProblemDetail
2. **Given** Authorization Bearer 无效 / 过期 / 签名错，**When** POST `/me/deletion-codes` 或 `/me/deletion`，**Then** 401（与 token 缺失同路径，反枚举）
3. **Given** 账号 status=FROZEN 持未过期 access token，**When** POST `/me/deletion-codes` 或 `/me/deletion`，**Then** 401（与 token 过期同路径，反枚举）
4. **Given** 账号 status=ANONYMIZED 持未过期 access token（罕见，TTL 跨过匿名化窗口），**When** POST `/me/deletion-codes` 或 `/me/deletion`，**Then** 401（同上）

---

### User Story 3 - SMS code 错误 / 过期 / 已用（Priority: P1，并列）

二次验证步骤的 SMS code 必须有限次尝试 + 过期 + 单次使用，避免暴力枚举。

**Why this priority**: 注销是不可逆 high-risk 操作，二次验证必须严格。

**Independent Test**: 续 User Story 1 拿到 code 后：

- 提交错误 6 位数字 → 401 INVALID_DELETION_CODE（per Edge Cases）；
- 等待 10min+ 后提交正确 code → 401（已过期，吞）；
- 同一 code 连续提交 2 次：第 1 次 204（成功 → status=FROZEN），第 2 次 401（已用 / 账号已 FROZEN，per User Story 2 AS-3 同路径吞）；

**Acceptance Scenarios**:

1. **Given** 持有正确 access token + 错误 6 位 code，**When** POST `/me/deletion` `{code}`，**Then** 401 INVALID_DELETION_CODE；DB account.status 不变（仍 ACTIVE）；DB account_sms_code attempts++ 计入限流
2. **Given** code 已过期（10min+），**When** POST `/me/deletion` `{code}`，**Then** 401（与"错码"字节级一致，反枚举）
3. **Given** 同 deletion code 5 次错误尝试，**When** 第 6 次再尝试任意 code，**Then** 429 + RATE_LIMITED + Retry-After（按 code 维度 throttle）
4. **Given** code 已使用（之前 transition FROZEN 成功），**When** 再次提交同 code，**Then** 401（per User Story 2 AS-3 — FROZEN 状态下任何请求 401）

---

### User Story 4 - 限流：发码 / 提交（Priority: P1，并列）

防止恶意重复发码（短信费用）+ 防止暴力提交码。

**Why this priority**: 短信费用 + 安全需要。

**Independent Test**: 同 account 60s 内连续调 `/me/deletion-codes` 第 2 次 → 429；同 IP 60s 内调 `/me/deletion` 11 次 → 第 11 次 429。

**Acceptance Scenarios**:

1. **Given** ACTIVE 账号 60s 内已发 1 次 deletion code，**When** 再次 POST `/me/deletion-codes`，**Then** 429 RATE_LIMITED + Retry-After（账号维度限流，60s 1 次）
2. **Given** 同 IP 60s 内已调 `/me/deletion` 10 次，**When** 第 11 次提交，**Then** 429（IP 维度限流，60s 10 次）

---

### Edge Cases

- **同账号在不同设备同时发起注销**：两台设备各自发码 → 第二次发码命中 60s 限流 429；若两次发码间隔 > 60s 则 DB 有 2 条 active code，提交其中任一可成功 transition；不影响最终 FROZEN 状态（幂等，per FR-006）
- **transition FROZEN 时 outbox 写失败**：整事务回滚（status 不变，refresh token 不 revoke）；客户端收到 5xx；下次重试 user 体验 = "再发码再提交"，account_sms_code 之前的 code 仍可用（未到 expiry）
- **freeze_until 时区**：始终 UTC `TIMESTAMPTZ`；client 显示由前端按 user locale 转换
- **transition FROZEN 后 cancel-deletion 流程立即可调**：本 spec 不定义 cancel；见 [`../cancel-deletion/spec.md`](../cancel-deletion/spec.md)
- **超时（10min 内未提交码）**：account_sms_code expires_at 到期；用户须重新调 `/me/deletion-codes`（计入限流）
- **deletion-codes 短信发送失败**（阿里云 SMS 服务挂）：返回 5xx；account_sms_code 不写入；用户重试不计入限流（per ADR-0011 SmsClient 异常路径）
- **POST `/me/deletion` body 缺 code 字段**：`VALIDATION_FAILED` 400（Jakarta `@NotBlank`）
- **POST `/me/deletion` body 含未知字段**：忽略（Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false`）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001（发码 endpoint）**：唯一 endpoint `POST /api/v1/accounts/me/deletion-codes`；无 request body；响应 204 No Content（不暴露 code 元数据，per phone-sms-auth FR-005 同 pattern）
- **FR-002（提交 endpoint）**：唯一 endpoint `POST /api/v1/accounts/me/deletion` `{code: string}`；响应 204 No Content（成功 transition）；失败按 FR-007 错误响应
- **FR-003（鉴权）**：两 endpoint 必须 `Authorization: Bearer <access_token>` 头；缺失 / 无效 / 过期 / 账号 status != ACTIVE → **统一 401 ProblemDetail**（不区分原因，per User Story 2 反枚举；与 account-profile FR-002 一致路径）
- **FR-004（SMS code 生成 + 持久化）**：6 位数字 code（`SecureRandom`），SHA-256 hex 存 `account.account_sms_code` 表（与既有 `phone-sms-auth` 路径同表 + `purpose='DELETE_ACCOUNT'` 区分），expires_at = now + 10min
- **FR-005（限流）**：
  - 发码：account 维度 60s 1 次（防短信费用滥发）；IP 维度 60s 5 次
  - 提交：account 维度 60s 5 次（每次错码计入），code 维度 5 次（正确 + 错误 attempts 总和）；IP 维度 60s 10 次
  - 超限 → 429 ProblemDetail + `Retry-After`
- **FR-006（FROZEN transition 原子性）**：transition 必须在单一 `@Transactional(rollbackFor = Throwable.class)` 内完成：
  1. 校验 code 通过 + 未过期 + 未用
  2. 标 code 已用（防重复 transition）
  3. account.status: ACTIVE → FROZEN + freeze_until = now + 15d
  4. revokeAllForAccount(account_id, now) on `account.refresh_token`（复用 logout-all 已有的 `RefreshTokenRepository.revokeAllForAccount`）
  5. publish `AccountDeletionRequestedEvent` 到 outbox（Spring Modulith Event Publication Registry）
  - 任一步失败 → 全部回滚；status 仍 ACTIVE，refresh_token 不变
- **FR-007（错误响应格式）**：所有错误遵循 RFC 9457 ProblemDetail（`application/problem+json`）；错误码：
  - `INVALID_DELETION_CODE` 401（错码 / 过期 / 已用 / 不属于本账号 — 字节级一致，反枚举）
  - `RATE_LIMITED` 429（FR-005 任一维度）
  - `VALIDATION_FAILED` 400（请求体 schema 错）
  - `SMS_SEND_FAILED` 503（短信服务异常，per FR-004 路径）
- **FR-008（事件）**：FROZEN transition 成功路径**必发** `AccountDeletionRequestedEvent`：
  - Payload：`accountId, freezeAt: Instant, freezeUntil: Instant`
  - Outbox：Spring Modulith Event Publication Registry（per modular-strategy.md 跨模块通信）
  - 当前消费方：本 spec 不要求任何模块订阅；M2+ pkm 模块引入后订阅做"软删除挂起标记"；M1.3 范围内仅写 outbox（无 listener consume）
- **FR-009（OpenAPI 暴露）**：Springdoc 自动从 controller 推导 schema；spec.md 不预定义字节
- **FR-010（不修改 phone-sms-auth FROZEN 行为）**：phone-sms-auth use case 已存在 FROZEN 反枚举（FR-005 + FR-006，per phone-sms-auth/spec.md User Story 3），本 spec 不改动；用户在 FROZEN 期间想撤销 → 走 `cancel-deletion` use case 的独立入口（不通过 phone-sms-auth），per [`../cancel-deletion/spec.md`](../cancel-deletion/spec.md)
- **FR-011（埋点）**：FROZEN transition 成功后埋点 `account_delete_init`（per PRD § 5.9）；本 spec 仅声明事件名，埋点接入由埋点模块统一处理（M2+ 引入；本期 commented `// TODO: emit telemetry account_delete_init`）

### Key Entities

- **Account（聚合根）**：扩展既有 Account
  - **新增字段** `freezeUntil: Instant | null`，nullable，仅 FROZEN 状态下非空（invariant）
  - 新增行为 `markFrozen(Instant freezeUntil, Instant now)`（package-private，经 `AccountStateMachine.markFrozen(Account, Instant freezeUntil, Instant now)` 调用，与既有 `markActive` / `markLoggedIn` / `changeDisplayName` pattern 一致）
- **AccountStateMachine（domain service）**：扩展既有 service
  - 新增 `markFrozen(Account, Instant freezeUntil, Instant now)`：校验 status == ACTIVE → 转 FROZEN + 写 freezeUntil；非 ACTIVE → `IllegalAccountStateException`（domain exception）
- **AccountSmsCode（既有聚合根，扩展 purpose）**：扩展 enum `Purpose` 加 `DELETE_ACCOUNT`（既有：`PHONE_SMS_AUTH`，per phone-sms-auth/spec FR-004）
- **AccountDeletionRequestedEvent（domain event，新增）**：record `(AccountId accountId, Instant freezeAt, Instant freezeUntil, Instant occurredAt)`，放 `mbw-account.api.event` 包（per modular-strategy 跨模块事件契约）
- **删除**：无

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**：发码 P95 ≤ 1.5s（含真实 SMS provider 调用，per PRD § 6.1 L2 档）
- **SC-002**：提交（FROZEN transition）P95 ≤ 300ms（含 DB write + outbox + refresh_token revoke + 事件发布，per L1 档）
- **SC-003**：FROZEN transition **原子性** — IT 模拟"FR-006 第 4 步 revoke 抛异常"路径 → 全部回滚（status 仍 ACTIVE + refresh_token 仍 active + outbox 无新事件）
- **SC-004**：FROZEN 后 token 失效 — IT：transition 成功后 refresh_token 全 401 + access token 调 `/me` 401
- **SC-005**：限流准确性 — FR-005 五条规则集成测试覆盖；429 + 正确 `Retry-After`
- **SC-006**：反枚举 — IT 验 4 类 401 响应（无 token / token 错 / 错码 / 过期码）字节级一致 + P95 时延差 ≤ 50ms
- **SC-007**：状态机不变量 — IT 验 ACTIVE → FROZEN 双步 transition 后 `freeze_until` 严格 = transition 时刻 + 15 days（容差 ≤ 1s）；ANONYMIZED / FROZEN 起始账号调 transition → 401 不改变
- **SC-008**：ArchUnit / Spring Modulith Verifier CI 仍 0 violation；新增 `AccountDeletionRequestedEvent` 在 `api.event` 包，可被任一模块通过 `api` 引用
- **SC-009**：OpenAPI snapshot 含两 endpoint + 错误响应描述
- **SC-010**：outbox 写入 — IT 验 transition 成功后 `event_publication` 表新增 1 条 `AccountDeletionRequestedEvent` 行（`completion_date` IS NULL，未消费）

## Clarifications

> 5 题于 2026-05-06 由 user 全部按推荐答案确认。落点已回写到对应 FR / Out of Scope / Assumptions 段。

### CL-001：二次验证用 SMS code only 还是 password + SMS（PRD § 5.5 旧版表述）

**Q**：PRD § 5.5 line 399 写 "输入当前密码 + 短信验证码（**同时**双重验证）"。但 ADR-0016 (2026-04-29) 决策 password 已废，主登录 = phone+SMS；§ 5.3 解绑流程已迁移到 "SMS 验证码 — 密码已废 per § 3.2"。注销二次验证应 follow §5.3 同 pattern（SMS only），还是保留 §5.5 旧表述（password + SMS）？

**推荐**：**SMS only**。理由：

- (1) ADR-0016 password 已废是不可逆决策；账号根本没 password 字段可校验；
- (2) 与 unbind 路径 §5.3 一致，UX 统一；
- (3) PRD § 5.5 表述早于 ADR-0016，应视为 stale；本 spec 落定后 PRD 应同步修订（单独 PR）。

**落点**：FR-001/FR-002 双 endpoint 设计已按 SMS only；CL-001 答案确认后无需改 spec，仅可能开 PRD 同步 PR。

### CL-002：FROZEN 后撤销入口为何不复用 phone-sms-auth 登录

**Q**：PRD § 5.5 line 386 "冻结期内登录流程" 描述 "登录触发撤销确认"。但 phone-sms-auth/spec.md FR-005 + User Story 3 设计为反枚举吞 — FROZEN 账号验证码正确仍返 INVALID_CREDENTIALS，不暴露 FROZEN 状态。两者矛盾；撤销注销入口在哪？

**推荐**：**dedicated `cancel-deletion` endpoint**（不复用 phone-sms-auth）。理由：

- (1) 反枚举不变性（phone-sms-auth FR-006 字节级一致）是安全资产，不能为撤销 UX 让出；
- (2) cancel-deletion 走独立路径 `POST /api/v1/auth/cancel-deletion-by-sms` `{phone, code}` — 服务端检查 phone 是否存在 FROZEN 账号，命中则 transition FROZEN → ACTIVE；不命中或 ACTIVE 状态则返 INVALID_CREDENTIALS（与"未注册"反枚举吞）；
- (3) UX 路径："app 启动 → 用户记得自己注销过 → 设置页 / 登录页有「撤销注销」入口（独立按钮）→ 输入 phone + SMS code → 撤销成功"。客户端 UI 单独引导，不混入登录流。

**落点**：本 spec 仅定义注销入口；撤销入口在 [`../cancel-deletion/spec.md`](../cancel-deletion/spec.md) 单独定义；本 spec FR-010 显式声明 phone-sms-auth 行为不变。

### CL-003：deletion-codes 与 phone-sms-auth 的 sms code 是同表还是新表

**Q**：发 SMS code 复用既有 `account.account_sms_code` 表 + 加 `purpose=DELETE_ACCOUNT` enum，还是新建 `account.account_deletion_code` 独立表？

**推荐**：**复用既有表 + purpose enum**。理由：

- (1) 同表 ⇒ 复用 `RateLimitService` 限流键 / cleanup scheduler / 索引 — 减少基础设施重复；
- (2) Schema 改动小（V8 加 `purpose VARCHAR NOT NULL DEFAULT 'PHONE_SMS_AUTH'` enum 列）；
- (3) 业务隔离靠 purpose enum + WHERE 子句保证 — phone-sms-auth 只查 `purpose='PHONE_SMS_AUTH'` 行，deletion 只查 `purpose='DELETE_ACCOUNT'` 行；不会串话；
- (4) 未来若引入 reset-password 等其他 SMS code 场景，purpose enum 易扩展。

**反方观点**：独立表 schema 干净，但成本不抵收益（M1.3 单一新场景，引入新表 + Mapper + Repository = 50% 代码翻倍）。

**落点**：FR-004 已按"复用 + purpose enum"设计；plan.md 落实 V8 migration 加 purpose 列 + 默认值兜底。

### CL-004：FROZEN 期间业务 endpoint 行为（GET /me / 其他业务 endpoint）

**Q**：PRD § 5.5 line 376-384 表格列出冻结期"修改资料/密码/解绑/绑定/改手机"全部禁止；GET /me 应允还是禁？登出（POST /api/v1/auth/logout-all）应允还是禁？

**推荐**：**全部 401（含 GET /me 与 logout-all）**。理由：

- (1) account-profile FR-009 已规定 "FROZEN/ANONYMIZED 拒接 token" — 一致路径；
- (2) FROZEN transition 时已 revoke 全部 refresh_token，access token TTL 内残留 5-15min，filter 层 status check 兜底拦截即可；
- (3) logout-all 也禁 → user 在 FROZEN 期"什么都做不了"是预期 UX（PRD 表格暗示），客户端检测 401 后引导到 cancel-deletion 入口；
- (4) 这等于 access token 验签后还需查 DB 验 status — `JwtAuthFilter` 已在 account-profile FR-009 落地此查询；本 spec 无需新增。

**落点**：FR-010 显式声明 — 不修改 JwtAuthFilter 行为，FROZEN 拒接 token 由 account-profile FR-009 现行逻辑兜底；本 spec 仅 transition 时撤销 refresh_token 不重复 access token 拒接逻辑。

### CL-005：FROZEN 期内重发 deletion-codes 是否允许（场景：用户 10min 内 code 过期想重新触发）

**Q**：deletion code 10min 过期后，用户在同 ACTIVE 账号下想重新发 → FR-005 限流 60s 1 次能 cover；但 ACTIVE → FROZEN 已 transition 后，FROZEN 账号显然不应再发 deletion-codes（无意义）。FR-003 401 拦截后用户 stuck 在 FROZEN，撤销路径走 cancel-deletion。但若 cancel-deletion 流程也要发 SMS code，怎么和 deletion code 区分？

**推荐**：**deletion code 仅 ACTIVE → FROZEN 用；cancel-deletion 流程独立用 `purpose='CANCEL_DELETION'` SMS code**（CL-003 复用表的好处）。理由：

- (1) 两 code purpose 物理隔离（同表不同 enum 值），无串话；
- (2) FROZEN 账号调 deletion-codes endpoint 直接 401（per FR-003 status 检查），不暴露状态；
- (3) cancel-deletion 流程在其 spec 内定义自己的 SMS code 流（独立 endpoint + purpose enum 值）。

**落点**：本 spec FR-004 仅引入 `purpose='DELETE_ACCOUNT'`；CL-005 答案确认后 cancel-deletion spec 引入 `purpose='CANCEL_DELETION'`。

## Assumptions

- **A-001**：复用既有 `RateLimitService`（per ADR-0011） / `JwtTokenIssuer` / `AccountRepository` / `RefreshTokenRepository.revokeAllForAccount`（logout-all 1.4 已落地）
- **A-002**：复用既有 `AccountSmsCode` 持久化基础设施 + `SmsClient`（per phone-sms-auth FR-004，已 production-ready）
- **A-003**：`AccountStateMachine` 已是 facade pattern（per account-profile FR + Account aggregate `markActive/markLoggedIn` invocation pattern）；新增 `markFrozen` 沿用同一 pattern
- **A-004**：M1.3 时点尚无 pkm 模块订阅 `AccountDeletionRequestedEvent`；outbox 写入但无 consumer，由 Spring Modulith Event Publication Registry 默认行为兜底（事件保留至消费 / 手工清理）
- **A-005**：M1.3 时点 `freezeUntil` 字段以 `account.account` 表 expand-only 加 nullable column 实现（per server CLAUDE.md § 五"无真实用户 + dev/staging" 跳步条件）；M3 引入真实用户前不会有 contract 步骤
- **A-006**：scheduler-driven anonymize（FROZEN → ANONYMIZED）单独 spec；本 spec 不定义 scheduler 行为
- **A-007**：`cancel-deletion` use case 单独 spec；本 spec 不定义其 endpoint / FR

## Out of Scope

- **scheduler-driven anonymize 行为** — 单独 spec `specs/account/anonymize-frozen-accounts/` （M1.3 后续）
- **cancel-deletion 流程**（FROZEN → ACTIVE 撤销）— [`../cancel-deletion/spec.md`](../cancel-deletion/spec.md)
- **匿名化字段处理细则**（phone null / nickname "已注销用户" / 三方 binding 删除）— anonymize-frozen-accounts spec 范围
- **PRD § 5.5 文本修订**（password 已废表述同步） — 单独 PR，不阻塞本 spec
- **埋点接入**（`account_delete_init`） — M2+ 埋点模块统一接入；本 spec FR-011 仅声明事件名
- **审计日志**（"何时何 IP 触发注销"） — `LoginAudit` 表已有，由 `JwtAuthFilter` / WebSecurityFilter 统一记录；本 spec 不引入新审计 entity
- **跨设备通知**（"另一设备已发起本账号注销"推送提示）— M3+ 评估，需 WebSocket / push notification 基础设施
- **批量行政注销**（admin 主动注销违规账号） — 不在本期；admin 模块 / RBAC 引入后单独 spec
- **注销前数据导出**（GDPR 含义的"被遗忘权" 配套数据下载） — M3+ 合规框架内评估

## References

- [PRD account-center.v2.md § 5.5 账号注销](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#55-账号注销)
- [`../account-state-machine.md`](../account-state-machine.md) — 状态定义 + invariants + error codes
- [`../phone-sms-auth/spec.md`](../phone-sms-auth/spec.md) — SMS code 流程 + 反枚举 pattern
- [`../account-profile/spec.md`](../account-profile/spec.md) — `/me` 鉴权 + FR-009 FROZEN 拒接 token
- [`../../auth/refresh-token/spec.md`](../../auth/refresh-token/spec.md) + [`../../auth/logout-all/spec.md`](../../auth/logout-all/spec.md) — refresh_token 持久化 + revokeAllForAccount
- [ADR-0016](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md) — password 已废 + 二次验证 SMS-only
- [ADR-0011](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0011-rate-limit-service.md) — RateLimitService
- [meta `docs/architecture/modular-strategy.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/architecture/modular-strategy.md) — 跨模块事件 + outbox
- 配套 use case：[`../cancel-deletion/spec.md`](../cancel-deletion/spec.md)
