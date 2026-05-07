# Feature Specification: Expose Frozen Account Status (account-center 反枚举边界调整 use case)

**Feature Branch**: `feature/expose-frozen-account-status-spec`
**Created**: 2026-05-07
**Status**: Draft（pending impl，docs-only PR；M1.X spec C 前置）
**Module**: `mbw-account`
**Input**: User description: "phone-sms-auth 在账号 status==FROZEN 时不再反枚举吞为 INVALID_CREDENTIALS 401，而是显式返 ACCOUNT_IN_FREEZE_PERIOD 403，让前端 spec C delete-account-cancel-deletion-ui 的 login flow 拦截 modal 拿到信号；ANONYMIZED 仍反枚举吞 401 不动。"

> **决策约束**：
>
> - **per [PRD § 5.4 + § 5.5 + § 7](../../../../docs/requirement/account-center.v2.md)**：FROZEN 账号登录返 `ACCOUNT_IN_FREEZE_PERIOD` HTTP 403 是 PRD 既定语义，本 spec 是 server 落地实现；当前 phone-sms-auth 实现把 FROZEN 与 ANONYMIZED 一并吞为 401 INVALID_CREDENTIALS（per [`../phone-sms-auth/spec.md`](../phone-sms-auth/spec.md) FR-005 第 3 分支 + FR-006 timing defense + SC-003 字节级一致），与 PRD § 5.4 描述存在 drift（per meta `MEMORY.md` 条 `reference_server_antienum_drift_vs_prd.md`）
> - **本 spec 仅修 FROZEN，不修 ANONYMIZED**（spec C 不需要 ANONYMIZED 信号；ANONYMIZED 是不可逆终态，反枚举防 phone 复用时序攻击的价值远高于 UX 收益）
> - **下游契约消费方**：no-vain-years-app `spec/delete-account-cancel-deletion-ui/spec.md` § 决策约束 + L13-14 + FR-010 + US4 — spec C `mapApiError` 扩展 case `ACCOUNT_IN_FREEZE_PERIOD → { kind: 'frozen' }` 触发拦截 modal；spec C `/speckit.implement` session 必须等本 spec ship 后才开
> - **HTTP status 形态**：HTTP 403 + `application/problem+json` body（RFC 9457 ProblemDetail）+ extended field `code: ACCOUNT_IN_FREEZE_PERIOD`（per PRD § 7 错误码表 + 与 mbw-account 既有 ProblemDetail 风格对齐 per `AccountWebExceptionAdvice`）；**不**采用 401-with-code-field 形态（语义混乱：401 在 HTTP 标准是 unauthenticated；而 FROZEN 用户实际通过了凭证校验，是 authorized-but-blocked）
> - **同 PR 修订 phone-sms-auth/spec.md**：FR-005 第 3 分支拆开 FROZEN/ANONYMIZED 单独表述；FR-006 timing defense 范围缩为 ANONYMIZED + 码错 + 未注册自动创建路径；SC-003 字节级一致路径数从 4 改 3 + 新增 "FROZEN 单独 disclosure path" 注释；防 spec drift > 1 week（per constitution Anti-Patterns）
> - **限流契约不变**：`auth:<phone>` 24h 5 次失败锁的"失败"定义不含 FROZEN 路径返 403（FROZEN 用户每次都得到同一 403，无凭证暴破语义；详见 FR-007）
> - **不引入新 endpoint / 不动 OpenAPI URL 路径**：仍是 `POST /api/v1/accounts/phone-sms-auth`，仅响应分支语义变更
> - **decision context**：用户 2026-05-07 4 项决策（仅 FROZEN 暴露 / 403+ProblemDetail / 新建独立 spec dir + 同 PR 改 phone-sms-auth / 不暴露 freeze_until 给前端消费）；其中 freeze_until 字段是否在 ProblemDetail body 暴露（即使前端不消费）留 `/speckit.clarify` 决（详见 Open Questions）

## User Scenarios & Testing _(mandatory)_

### User Story 1 — FROZEN 账号 + 正确 SMS 码 → 403 ACCOUNT_IN_FREEZE_PERIOD（Priority: P1）

注销冻结期内（status=FROZEN，freeze_until > now）的账号在 `POST /api/v1/accounts/phone-sms-auth` 提交合法 phone + 合法 SMS 码 → 服务端不再反枚举吞为 401 INVALID_CREDENTIALS，而是返 HTTP 403 + ProblemDetail body `{type, title, status:403, code:"ACCOUNT_IN_FREEZE_PERIOD", ...}`。**不签 token，不更新 last_login_at，不解除冻结**（per PRD § 5.4 表 + § 5.5 第 3 步）。

**Why this priority**: 是本 spec 的全部价值。前端 spec C login flow 拦截 modal 完全依赖此信号触发；当前 401 INVALID_CREDENTIALS 形态下 spec C `mapApiError` 无法区分"码错"vs"frozen"，撤销注销 UX 入口失效。

**Independent Test**: Testcontainers PG + Redis；预设 FROZEN 账号 `+8613800138001`，`freeze_until = now + 14d`；客户端发 `POST /api/v1/accounts/sms-codes {phone}` → 拿真实 SMS code → 发 `POST /api/v1/accounts/phone-sms-auth {phone, code}` → 断言:

1. HTTP 状态码 = 403
2. Response Content-Type = `application/problem+json`
3. Response body 包含 `code: "ACCOUNT_IN_FREEZE_PERIOD"`
4. DB `account.last_login_at` 字段未更新（与请求前一致）
5. DB `account.status` 仍 FROZEN，`freeze_until` 不变
6. DB `account.refresh_token` 该 accountId 无新行（未签 token）

**Acceptance Scenarios**:

1. **Given** FROZEN 账号 `+8613800138001`（freeze_until=now+14d）+ 合法 SMS code，**When** POST `/api/v1/accounts/phone-sms-auth` `{phone, code}`，**Then** HTTP 403 + body code=`ACCOUNT_IN_FREEZE_PERIOD`；DB `account` 表 last_login_at / status / freeze_until 全不变；DB `account.refresh_token` 表无新行
2. **Given** 同账号 + 错误 SMS code（仍在码 5min TTL 内但码值错），**When** POST `/api/v1/accounts/phone-sms-auth`，**Then** HTTP 401 + body code=`INVALID_CREDENTIALS`（per phone-sms-auth FR-005 码错路径不变；timing defense 仍走）
3. **Given** 同账号 + 过期 SMS code（已超 5min TTL），**When** POST，**Then** 同上 401 INVALID_CREDENTIALS（码过期与码错语义合并）
4. **Given** 同账号 + 合法 SMS code 但 `freeze_until` 已过去（理论上凌晨定时任务未跑前的窗口），**When** POST，**Then** 仍走 FROZEN 分支返 403（status 字段是真相源；freeze_until 仅供 anonymize-frozen-accounts scheduler 扫；本 use case 不主动 transition）

---

### User Story 2 — ANONYMIZED 账号 + 正确码 → 401 INVALID_CREDENTIALS 反枚举吞（Priority: P1，并列）

已匿名化（status=ANONYMIZED）账号尝试登录时（理论上 phone 字段已被 NULL，匹配不到此账号；但若 phone 重新被新账号占用前的边界场景仍可能命中），server 保持现有反枚举吞为 401 INVALID_CREDENTIALS 行为不变。攻击者无法区分"已匿名化"vs"未注册"vs"码错"。

**Why this priority**: 反枚举安全基线；ANONYMIZED 是不可逆终态，反枚举防止 phone 时序复用攻击与个保法 / GDPR-ish 数据脱敏要求一致。spec C 不消费 ANONYMIZED 信号（spec C 走 cancel-deletion 流程已经隐含 phone 仍属用户记忆中的活账号）。

**Independent Test**: Testcontainers；预设 ANONYMIZED 账号（id=1001，phone=NULL，status=ANONYMIZED）+ 同 phone 走未注册自动创建路径建立新 ACTIVE 账号（id=1002）；用 1002 的 phone 走完整流程 → 应走 ACTIVE 成功路径（与 ANONYMIZED 旧账号无关联）。直接验证 ANONYMIZED 账号需绕过 phone NULL 约束 → 用单测覆盖 use case branch（per phone-sms-auth tasks T1-test "ANONYMIZED 反枚举" case 既有，本 spec 不改）。

**Acceptance Scenarios**:

1. **Given** ANONYMIZED 账号（极端边界）+ 假设可触发 status==ANONYMIZED 分支 + 合法 SMS code，**When** POST `/phone-sms-auth`，**Then** HTTP 401 + body code=`INVALID_CREDENTIALS`（与码错路径字节级一致）
2. **Given** ANONYMIZED 账号 phone 已置 NULL + 新用户用同 phone 注册，**When** POST `/phone-sms-auth`，**Then** 走未注册自动创建路径 → HTTP 200 + 新 accountId（旧 ANONYMIZED 账号不恢复，per PRD § 5.5 不可逆）

---

### User Story 3 — phone-sms-auth 4 主路径 baseline regression 不变（Priority: P1，并列）

本 spec 仅修 FROZEN 单分支语义，**其他 3 主路径行为完全不变**：① ACTIVE 成功 / ② 未注册自动创建成功 / ③ 码错。回归 IT 必须保证 spec D ship 后这 3 路径行为字节级、status code、wall-clock latency 与 spec D 前一致（除 timing defense pad 边界变化，详见 US4）。

**Why this priority**: 本 spec 是 surgical change（per 用户全局 CLAUDE.md § 3 "Surgical Edits"）；改一个分支不能 cascading 破坏其他分支。phone-sms-auth `UnifiedPhoneSmsAuthUseCaseTest` + IT suite 必须保留并续绿。

**Independent Test**: 复用 phone-sms-auth 既有 `UnifiedPhoneSmsAuthUseCaseTest` 8 case + `SingleEndpointEnumerationDefenseIT`（修订后版本，per US4）+ 新增 spec D 自身 IT；ACTIVE / 未注册自动创建 / 码错 3 路径继续 GREEN。

**Acceptance Scenarios**:

1. **Given** ACTIVE 账号 + 合法 SMS code，**When** POST `/phone-sms-auth`，**Then** HTTP 200 + `{accountId, accessToken, refreshToken}`；DB `account.last_login_at` 更新；行为与 spec D 前一致
2. **Given** 未注册 phone + 合法 SMS code，**When** POST `/phone-sms-auth`，**Then** HTTP 200 + 新 accountId（status=ACTIVE）；outbox `AccountCreatedEvent`；行为与 spec D 前一致
3. **Given** 任意 phone（已注册 ACTIVE / 未注册 / FROZEN / ANONYMIZED）+ 错误 SMS code，**When** POST，**Then** HTTP 401 + body code=`INVALID_CREDENTIALS`（码错路径仍然 4 子分支字节级一致）
4. **Given** 限流命中（per phone-sms-auth FR-007 `auth:<phone>` 24h 5 次失败），**When** POST，**Then** HTTP 429 + `Retry-After`（限流契约不变；FROZEN 路径不计入失败计数详见 FR-007）

---

### User Story 4 — 反枚举不变量缩窄后 3 路径字节级一致 + P95 时延差 ≤ 50ms（Priority: P1，并列）

spec D ship 后，phone-sms-auth `SingleEndpointEnumerationDefenseIT` 验证的反枚举字节级一致路径**从 4 路径减为 3 路径**：

| 路径 | spec D 前 | spec D 后 |
|---|---|---|
| ACTIVE 成功 | ✅ 与失败路径字节级一致（200 + token） | ✅ 不变（200 + token）|
| 未注册自动创建成功 | ✅ 与 ACTIVE 成功 + 失败路径字节级一致（200 + token） | ✅ 不变（200 + token）|
| ANONYMIZED + 正确码（边界）| ✅ 反枚举吞为 401 INVALID_CREDENTIALS | ✅ 不变 |
| 码错 / 码过期 / 任意状态 | ✅ 401 INVALID_CREDENTIALS | ✅ 不变 |
| **FROZEN + 正确码** | ✅ **被错误吞为 401 INVALID_CREDENTIALS（drift）** | ❌ **403 ACCOUNT_IN_FREEZE_PERIOD（显式 disclosure）** |

`SingleEndpointEnumerationDefenseIT` 修订后断言 3 路径（ACTIVE 成功 / 未注册自动创建 / ANONYMIZED + 码错共反枚举吞）字节级响应 + P95 时延差 ≤ 50ms；FROZEN 路径单独由本 spec 新建 IT `FrozenAccountStatusDisclosureIT` 验证 disclosure 行为。

**Why this priority**: 反枚举安全基线收缩面必须可测、可回归；不能让 spec D 引入的 disclosure 行为意外渗入其他路径。

**Independent Test**: 修订 `SingleEndpointEnumerationDefenseIT` 1000 次请求（混合 3 路径）→ status / body / headers 字节级一致 + P95 时延差 ≤ 50ms；新增 `FrozenAccountStatusDisclosureIT` 验证 FROZEN 单独 100 次请求 → 全部 status=403 + body code=ACCOUNT_IN_FREEZE_PERIOD。

**Acceptance Scenarios**:

1. **Given** 1000 次混合请求（3 路径各 ~333 次），**When** 跑 `SingleEndpointEnumerationDefenseIT`，**Then** 失败路径（ANONYMIZED + 码错）+ 成功路径（ACTIVE / 未注册自动创建）字节级一致；P95 时延差 ≤ 50ms
2. **Given** FROZEN 账号 100 次请求，**When** 跑 `FrozenAccountStatusDisclosureIT`，**Then** 全部 status=403 + body code=ACCOUNT_IN_FREEZE_PERIOD；wall-clock 不要求与 3 路径对齐（disclosure 已显式承认）
3. **Given** 攻击者比较 FROZEN 路径与其他路径响应，**When** 观察 status code，**Then** **能区分**（FROZEN=403，其他=401/200，per Q1 决策已显式接受此 trade-off）

---

### User Story 5 — FROZEN 路径 timing defense 不参与 wall-clock pad（Priority: P2）

本 spec 改动后 FROZEN 路径直接抛 `AccountInFreezePeriodException` 透过 web advice 返 403，**不再走** `TimingDefenseExecutor.executeInConstantTime` 的 400ms wall-clock pad。原因：① FROZEN 已显式 disclosure（不需 timing 隐藏） ② 用户被 frozen 拦截后 UX 应快速反馈（少等 ~400ms 体验更好） ③ 减少无意义 CPU/线程时间。

**Why this priority**: UX + 资源效率优化，非阻塞性；P2 优先级允许 plan.md 阶段技术决策（在 use case 层 catch FROZEN 异常 / 在 TimingDefenseExecutor 加 bypass 参数 / FROZEN 检查提前到 timing executor 包裹之外）灵活决定。

**Independent Test**: 新增 unit test `UnifiedPhoneSmsAuthUseCaseTest` case "FROZEN path bypasses timing defense" — mock 一个 FROZEN account fixture，stopwatch 测 use case execute() wall-clock 应 < 400ms（理想 < 100ms）；对照 ANONYMIZED case wall-clock 仍 ≥ 380ms（保留 timing defense pad）。

**Acceptance Scenarios**:

1. **Given** FROZEN account fixture + mock 快路径（无真实 BCrypt / DB IO），**When** `useCase.execute(cmd)`，**Then** wall-clock < 100ms（不进 timing pad）
2. **Given** ANONYMIZED account fixture + mock 同条件，**When** `useCase.execute(cmd)`，**Then** wall-clock 仍 ≥ 380ms（timing pad 保留，per phone-sms-auth FR-006 修订后范围）
3. **Given** ACTIVE 成功 fixture + mock，**When** `useCase.execute(cmd)`，**Then** wall-clock 仍 ≥ 380ms（timing pad 保留）

---

### Edge Cases

- **FROZEN 账号 freeze_until 已过期但 anonymize-frozen-accounts scheduler 还没跑**（凌晨 03:00 cron 之前的窗口）：`account.status` 仍 FROZEN（DB 真相源），本 use case 仍走 FROZEN 分支返 403；scheduler 后续会 transition 到 ANONYMIZED；用户 UX：能撤销注销
- **FROZEN 账号 freeze_until 字段为 NULL**（数据异常，理论不可能）：fall through 到 ANONYMIZED 分支处理 / 仍走 FROZEN 路径（plan.md 决，建议保守 fallback 到 401 INVALID_CREDENTIALS 反枚举吞，避免暴露异常 status）
- **同一 FROZEN 账号短时间内重复请求**：每次都返 403 ACCOUNT_IN_FREEZE_PERIOD；`auth:<phone>` 限流计数**不计入**（per FR-007，FROZEN 非凭证暴破语义）；但 `sms:<phone>` 60s 限流 / 24h 10 次仍生效（保护 SMS 资源）
- **SMS code 已被 FROZEN 路径消费的 race**：本 spec FROZEN 分支位于 SMS code verify 之后（per phone-sms-auth FR-008 顺序），码已 Redis DEL；用户拿到 403 后需重新发码（60s cooldown）— 这是与 spec C Q3 决议简化 UX 的轻度 friction 点，留 `/speckit.clarify` 评估是否调整顺序
- **并发同 phone race**：FROZEN 账号判定瞬间 cancel-deletion 流并发完成 → 该账号 status 已转 ACTIVE → 本 use case 内存中 `existing.get()` 仍为 FROZEN 快照 → 返 403（用户感知到 race 后下次请求即走 ACTIVE 成功路径，self-healing）；不引入额外悲观锁（per Surgical Edits）
- **MissingAuthenticationException 等其他 advice 拦截优先级**：`AccountWebExceptionAdvice` 当前 `@Order(HIGHEST_PRECEDENCE + 100)`，新增 `AccountInFreezePeriodException` handler 同优先级；不会被 mbw-shared GlobalExceptionHandler 抢先拦
- **OpenAPI Springdoc schema 同步**：新增 `AccountInFreezePeriodException` handler 后，Springdoc 自动反射出 403 response schema 含 ProblemDetail + extended `code` field；前端 `pnpm api:gen` 拉到含 `ACCOUNT_IN_FREEZE_PERIOD` 的 client（Springdoc 自动生成，本 spec 不需要手 annotation）
- **FROZEN 账号 + 错误 SMS code**：先走 SMS verify 返 401 INVALID_CREDENTIALS（码错路径优先），不暴露 FROZEN status；本 spec 仅"码正确 + FROZEN" 单一组合触发 disclosure
- **FROZEN 账号 + 合法 phone 但 IP 限流命中**：先走 IP `sms:<ip>` 限流返 429（per phone-sms-auth FR-007），不触发 FROZEN disclosure 路径
- **新 endpoint 是否需要单独 OpenAPI tag**：不需要，仍属 `accounts` tag（不引入新 endpoint，仅响应分支语义变更）
- **ProblemDetail body 是否含 traceId**：复用 mbw-shared GlobalExceptionHandler / Spring 默认行为；本 spec 不改 traceId 注入路径

---

## Functional Requirements _(mandatory)_

| ID     | 需求 |
| ------ | ---- |
| FR-001 | **新增 domain 异常**：`com.mbw.account.domain.exception.AccountInFreezePeriodException`，extends `RuntimeException`；含 `public static final String CODE = "ACCOUNT_IN_FREEZE_PERIOD"`；构造参数 `Instant freezeUntil` + getter（per CL-001 暴露给 web advice 注入 ProblemDetail body extended field）；零 framework 依赖（per constitution domain 层零 framework）；**含 javadoc disclosure 段**（per CL-005）：`<p><b>Disclosure boundary</b>: this exception is intentionally NOT routed through anti-enumeration uniform 401 (per spec D expose-frozen-account-status FR-001~FR-004). FROZEN status is explicitly disclosed to support spec C login flow cancel-deletion modal; ANONYMIZED status remains anti-enumeration-collapsed via InvalidCredentialsException. Do not collapse this back to InvalidCredentialsException without revisiting spec D.` |
| FR-002 | **修改 use case 分支语义**：`UnifiedPhoneSmsAuthUseCase.doExecute` line 132-134 的 FROZEN/ANONYMIZED 共抛 `InvalidCredentialsException` 拆开 — `account.status == FROZEN` → 抛 `new AccountInFreezePeriodException(account.freezeUntil())`；`account.status == ANONYMIZED` 仍抛 `InvalidCredentialsException`（反枚举吞）；ACTIVE 成功 / 未注册自动创建 / 码错 / 码过期路径行为完全不变 |
| FR-003 | **新增 web advice handler**：`AccountWebExceptionAdvice` 加 `@ExceptionHandler(AccountInFreezePeriodException.class)` method，构造 `ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Account in freeze period; cancel deletion to re-activate")` + `setTitle("Account in freeze period")` + `setProperty("code", AccountInFreezePeriodException.CODE)` + `setProperty("freezeUntil", ex.getFreezeUntil().toString())`（per CL-001 ISO 8601 UTC）；`@Order` 与既有 advice 同优先级（HIGHEST_PRECEDENCE + 100） |
| FR-004 | **timing defense bypass FROZEN**：`TimingDefenseExecutor.executeInConstantTime` 加可选 `Predicate<Throwable> bypassPad` 参数（per CL-003）；既有 2-arg signature 通过 default predicate=`always-false` 保留向后兼容；`UnifiedPhoneSmsAuthUseCase.execute` 调用时显式传 `e -> e instanceof AccountInFreezePeriodException`；FROZEN 路径异常 catch 后跳过 padRemaining，wall-clock < 100ms；ANONYMIZED + 码错 + 未注册自动创建 + ACTIVE 成功路径仍 pad 到 400ms 字节级一致 |
| FR-005 | **限流契约不变**：`auth:<phone>` 24h 5 次失败锁的"失败"定义**不含 FROZEN 路径返 403**（FROZEN 用户每次都得到同一 403，无凭证暴破语义；若计入会让 frozen 用户重试 5 次后被锁，无法触发 cancel-deletion modal — UX 倒退）；ANONYMIZED + 码错 + INVALID_CREDENTIALS 路径仍计入失败计数（per phone-sms-auth FR-007 行为不变） |
| FR-006 | **SMS code 消费时机不变**（per CL-002）：本 spec **不**调整 phone-sms-auth FR-008 顺序 — 限流 → SMS verify (Redis DEL) → DB lookup → status 分支；FROZEN 分支位于 SMS verify 之后，码已被消费；用户拿到 403 后 UX：tap modal "撤销" → 跳 cancel-deletion screen → 重发码（cancel-deletion 走独立 `cancel:<phone>` bucket，与 `sms:<phone>` 60s cooldown 解耦，无 friction）；alternative（FROZEN 分支不消费码 / status check 提前到 SMS verify 之前）会引入新一组 race 风险，复杂度超本 spec scope |
| FR-007 | **错误码归属**：`ACCOUNT_IN_FREEZE_PERIOD` 是 `mbw-account` 模块特有错误码（per server `CLAUDE.md` § 三 错误码归属表第 1 档）；常量定义在 `AccountInFreezePeriodException.CODE`（per server 既有 enum-as-static-constant pattern，per `InvalidCredentialsException` / `InvalidDeletionCodeException`）；M1 单体阶段不加模块前缀（per CLAUDE.md § 三 命名冲突处理） |
| FR-008 | **同 PR 修订 phone-sms-auth/spec.md**：作为本 spec D PR 的一部分（防 spec drift > 1 week，per constitution Anti-Patterns），同 PR 修改 `spec/account/phone-sms-auth/spec.md` 三处：① FR-005 第 3 分支（"present + FROZEN/ANONYMIZED → InvalidCredentialsException"）拆开为 FROZEN 单独 + ANONYMIZED 单独两子分支；② FR-006 timing defense 范围明示 "缩为 ANONYMIZED + 码错 + 未注册自动创建路径，FROZEN 路径已显式 disclosure 不参与"；③ SC-003 字节级一致路径数从 "4 种分支" 改 "3 种分支"（ACTIVE 成功 / 未注册自动创建 / ANONYMIZED + 码错共反枚举吞）+ 加注释 "FROZEN 单独由 spec D `expose-frozen-account-status` 验证 disclosure 行为"；④ Clarifications 段加新条 CL-006 引用 spec D 改动 |
| FR-009 | **OpenAPI 自动同步**：Springdoc 反射出 `POST /api/v1/accounts/phone-sms-auth` 新增 403 response schema（ProblemDetail + extended `code` field）；本 spec **不**手写 `@ApiResponse(responseCode = "403", ...)` annotation（per constitution "OpenAPI = single source of truth"，Springdoc 从 `@ExceptionHandler` 自动反射）；前端 spec C impl 阶段 `pnpm api:gen` 拉到含 ACCOUNT_IN_FREEZE_PERIOD 的 client |
| FR-010 | **不引入新 endpoint / DB schema 变更 / 新 migration**：本 spec 仅 use case + web advice + domain exception 层改动；零 Flyway migration；零跨模块依赖变更；零 mbw-shared / 跨 schema 改动 |

---

## Success Criteria _(mandatory)_

| ID | 标准 | 测量方式 |
| --- | --- | --- |
| SC-001 | 新建 IT `FrozenAccountStatusDisclosureIT`：FROZEN 账号 + 合法 SMS code → HTTP 403 + body code=`ACCOUNT_IN_FREEZE_PERIOD` + DB account 表 last_login_at / status / freeze_until 全不变 + DB refresh_token 表无新行 | Testcontainers PG + Redis；assertj 断言 |
| SC-002 | 修订 IT `SingleEndpointEnumerationDefenseIT`：3 路径（ACTIVE 成功 / 未注册自动创建 / ANONYMIZED + 码错共反枚举吞）1000 次混合请求字节级响应一致 + P95 wall-clock 时延差 ≤ 50ms；FROZEN 路径**显式从此 IT 中移除**（移到 SC-001 单独 IT）| Testcontainers + assertj + 1000 次循环 + Math.abs(p95Diff) ≤ 50ms |
| SC-003 | 既有 unit test `UnifiedPhoneSmsAuthUseCaseTest` 8 case 续绿；新增 case "FROZEN path → AccountInFreezePeriodException with freezeUntil" + "FROZEN path bypasses timing defense (wall-clock < 100ms)" + "ANONYMIZED path retains InvalidCredentialsException + timing defense pad ≥ 380ms" | `./mvnw -pl mbw-account test` 全绿 |
| SC-004 | 错误码 `ACCOUNT_IN_FREEZE_PERIOD` 在 OpenAPI spec 出现 — `curl http://localhost:8080/v3/api-docs \| jq` 含 `phone-sms-auth` POST 的 `responses.403.content.application/problem+json.schema.properties.code.enum` 含此值（或类似路径，per Springdoc 默认 schema 形态） | `mvn spring-boot:run` 后 curl + jq 断言 |
| SC-005 | phone-sms-auth/spec.md 同 PR 修订完成 — `git diff spec/account/phone-sms-auth/spec.md` 显示 FR-005 / FR-006 / SC-003 三处修订 + 新增 Clarifications CL-006 | git diff review |
| SC-006 | 既有 phone-sms-auth IT suite 全绿（不破坏 baseline）— `RegisterByPhoneE2EIT` / 任何 controller-level test 不退化 | `./mvnw -pl mbw-app verify` |
| SC-007 | 真后端冒烟（impl 阶段执行）：① 通过 admin SQL 把测试账号 status 改 FROZEN + freeze_until=now+14d ② curl POST `/api/v1/accounts/sms-codes` ③ curl POST `/api/v1/accounts/phone-sms-auth` ④ 断言 403 + body 含 ACCOUNT_IN_FREEZE_PERIOD ⑤ 截图归档 | 手动跑 + `runtime-debug/2026-05-XX-expose-frozen-account-status-smoke/` |
| SC-008 | spec D 改动行数 ≤ 200 LOC（excluding spec.md / plan.md / tasks.md / IT）— per Surgical Edits + Senior Engineer Test 自查 | `git diff --stat HEAD~1 -- 'mbw-account/src/main/**/*.java'` 验证 |
| SC-009 | ArchUnit + Spring Modulith Verifier CI 0 violation — domain 层 `AccountInFreezePeriodException` 零 framework 依赖；`ModuleStructureTest` 续绿 | `./mvnw test -pl mbw-app -Dtest=ModuleStructureTest` |
| SC-010 | 前端 SDK 同步 readiness：spec D ship 后 server `/v3/api-docs` 含 ACCOUNT_IN_FREEZE_PERIOD；spec C impl 阶段 `pnpm api:gen` 拉取后 generated client 含此错误码；spec C `mapApiError` 可消费 | curl + 后续 spec C impl 阶段验证 |

---

## Clarifications

> `/speckit.clarify` round 1 — 2026-05-07,5 项 cross-cutting 全部决议。Q-CL-001~Q-CL-004 由用户拍板（Recommended 候选）；Q-CL-005 由 Claude 自决（doc-only 安全侧偏好）。

### CL-001 — ProblemDetail body 是否暴露 freezeUntil 字段

- **决议**：(a) 暴露 ISO 8601 UTC `freezeUntil` 字段
- **理由**：与 PRD § 5.5 第 3 步原文 "返回 ACCOUNT_IN_FREEZE_PERIOD + freeze_until" 一致；前端 spec C Q3 选择不消费天数（简化 UX 文案不显示），但后端不剥夺该信号；为 PHASE 2 / M2 mockup 留扩展口（万一未来 UX 想显示"剩余 N 天"无需后端改动）；成本接近 0（RFC 9457 标准扩展字段）；alternative (b) 不暴露 = 与 PRD 原文 drift；alternative (c) `freezeRemainingSeconds` 数值字段 = 与 PRD 字段名 drift + 前端要算绝对时间增 friction
- **落点**：FR-001 `AccountInFreezePeriodException` 构造参数 `Instant freezeUntil`；FR-003 web advice handler `setProperty("freezeUntil", ex.getFreezeUntil().toString())`；OpenAPI Springdoc 自动反射 schema

### CL-002 — FROZEN 路径返 403 时 SMS code 是否消费

- **决议**：(a) 保留现有顺序，FROZEN 路径码已被消费（Redis DEL）
- **理由**：与 phone-sms-auth FR-008 顺序一致（限流 → SMS verify+消费 → DB lookup → status 分支）；本 spec D 不动 use case 顺序逻辑（Surgical Edits）；FROZEN 用户拿 403 后 UX：tap modal "撤销" → 跳 cancel-deletion screen → 重发码（cancel-deletion 走独立 `cancel:<phone>` bucket，与 `sms:<phone>` 60s cooldown 解耦，无 friction）；alternative (b) FROZEN 不消费码 = 引入新一组 race（同码两次使用？反枚举退化）+ 复杂度超本 spec scope；alternative (c) revert API = SmsCodeService 未提供，过度复杂
- **落点**：FR-006 显式声明顺序不变 + 用户 UX 转 cancel-deletion 重发码；Edge Cases 段已含此条

### CL-003 — TimingDefenseExecutor bypass FROZEN 实现策略

- **决议**：(a) `TimingDefenseExecutor.executeInConstantTime` 加 `Predicate<Throwable> bypassPad` 参数（可选第 3 参数）
- **理由**：侵入性低（既有 2-arg signature 通过 default predicate=`always-false` 保留向后兼容）；语义清晰（caller 显式声明哪类异常 bypass）；可复用其他需要 bypass 的场景（M2+ 若有新 disclosure 异常）；FROZEN 路径 wall-clock < 100ms 体验改善；alternative (b) use case 层 split 提前 findByPhone = 2x DB 查询 + 反模式（违反 single-pass pattern）；alternative (c) 默认对 domain.exception 包 bypass = 语义错误（InvalidCredentialsException 必须仍 pad，反枚举吞）+ 误伤其他异常
- **落点**：FR-004 timing defense bypass 策略明示 bypassPredicate 参数；plan.md `TimingDefenseExecutor` 新签名 + 既有 callsites（仅 phone-sms-auth use case 1 处）改造 + bypass predicate `e -> e instanceof AccountInFreezePeriodException`

### CL-004 — SingleEndpointEnumerationDefenseIT 修订 vs 新建

- **决议**：(a) 修订既有 IT，从 4 路径改 3 路径
- **理由**：改动小（1 file diff 内）；spec D 改动语义清晰（git diff 直观显示 "FROZEN 路径 disclosure 后从 enumeration defense IT 中移除"）；保持 IT class 数量稳定（不增 testfile 数量）；FROZEN 路径单独由本 spec 新建 `FrozenAccountStatusDisclosureIT` 验证 disclosure 行为（per SC-001）；alternative (b) 新建 + 删除既有 = file-level diff 略清晰但 git history 跟踪麻烦；alternative (c) `@Disabled` 旧 IT = 违反 constitution `@Disabled > 5 个告警`
- **落点**：tasks.md `[Test]` task 标 "修订 SingleEndpointEnumerationDefenseIT 删 FROZEN case + 加注释指向新 SC-001 IT"；SC-002 已含修订断言

### CL-005 — AccountInFreezePeriodException javadoc 是否标注 disclosure intent

- **决议**：(a) 加 javadoc 段 `<p><b>Disclosure boundary</b>: ...`
- **理由**：明示 intent — 防后续维护者把这条异常误合并回 InvalidCredentialsException 的反枚举吞（commit message / PR description 会随时间淡出，javadoc 与代码同位永久 trace）；安全相关代码 self-documenting 是 best practice；成本极低（5 行 javadoc）
- **落点**：FR-001 javadoc 内容说明 `<p><b>Disclosure boundary</b>: this exception is intentionally NOT routed through anti-enumeration uniform 401 (per spec D expose-frozen-account-status FR-001~FR-004). FROZEN status is explicitly disclosed to support spec C login flow cancel-deletion modal; ANONYMIZED status remains anti-enumeration-collapsed via InvalidCredentialsException. Do not collapse this back to InvalidCredentialsException without revisiting spec D.`

---

## Assumptions & Dependencies

- **PRD § 5.4 + § 5.5 + § 7 既有**：`ACCOUNT_IN_FREEZE_PERIOD` HTTP 403 是 PRD 既定语义（per docs/requirement/account-center.v2.md L390 + L513-514）；spec D 只是 server 落地实现
- **AccountStatus enum 既有**：domain 层 `AccountStatus.FROZEN` / `ANONYMIZED` / `ACTIVE` 已就位（per phone-sms-auth + cancel-deletion + anonymize-frozen-accounts 三 use case impl）；本 spec 不新增 enum value
- **`AccountStateMachine.canLogin(account)` 既有**：返 false for FROZEN/ANONYMIZED；本 spec 不改此 domain service，而是在 use case 层拆开 `!canLogin` 后的细分判断
- **`Account.freezeUntil()` getter 既有**：domain model 已暴露此字段（per delete-account use case 落地 V7 migration `account.freeze_until` 列 + Account domain model）；本 spec 直接消费
- **`AccountWebExceptionAdvice` 既有**：扩展现有 advice 加新 handler；不新建 advice 类（避免 @Order 冲突 / 双 advice 拦截语义混乱）
- **`TimingDefenseExecutor` 既有**：本 spec 可能小改（加 bypass 参数）/ 不改（use case 层 FROZEN 提前判定）；具体由 plan.md 决（per FR-004 + Q-CL-003）
- **OpenAPI Springdoc 自动同步**：既有 controller 改 advice 后 Springdoc 自动反射 403 response schema；本 spec 不手写 OpenAPI annotation
- **下游 spec C 已对此契约假设**：no-vain-years-app `spec/delete-account-cancel-deletion-ui/spec.md` 已写明 spec D 落地 `phone-sms-auth FROZEN → ACCOUNT_IN_FREEZE_PERIOD` 错误码；spec C `/speckit.implement` session 等本 spec ship 后才开
- **本 spec 不依赖 spec C 任何改动 ship**：spec D 是 server-only，可独立合并；spec C 是 spec D 的下游消费方
- **现有 IT `SingleEndpointEnumerationDefenseIT` 修订是破坏性变更**：删除 4 路径之 1（FROZEN）→ 3 路径；不引入向后兼容（M1 阶段无真实用户，per phone-sms-auth CL-002 同款决议）

---

## Out of Scope（M1.X 显式不做）

- **ANONYMIZED 状态 disclosure**：per Q1 决策，ANONYMIZED 仍反枚举吞 401；本 spec **不**新增 `ACCOUNT_ANONYMIZED` 错误码
- **`IDENTITY_IN_FREEZE_PERIOD` (HTTP 409) 行为**：PRD § 7 已定义但 mbw-account 当前未实现 third-party binding 流程；M1.3 微信 OAuth use case 时再起 spec
- **客户端 isFrozenLocal 备用方案**：per Q1 拒绝 C 选项；本 spec 走 server 显式 disclosure
- **freeze_until 字段前端消费**：per spec C Q3 决议，前端不消费天数；后端是否在 ProblemDetail body 暴露该字段留 Q-CL-001 决；前端 PHASE 2 mockup 落地后若需消费再起新 spec
- **rate limit "FROZEN 路径计入失败计数"**：per FR-005 显式排除；防 frozen 用户重试 5 次被锁
- **FROZEN 路径不消费 SMS code 的优化**：per FR-006 + Q-CL-002 推迟到后续评估；本 spec 保留现有顺序
- **撤销注销 endpoint 改动**：cancel-deletion endpoint 已 M1.3 落地（PR #131-138），本 spec 不动
- **`auth:<phone>` 限流 bucket 重构**：per phone-sms-auth FR-007 不动；本 spec 仅澄清 FROZEN 路径不计入失败的语义
- **OpenAPI spec 手写 annotation**：per FR-009 走 Springdoc 自动反射
- **DB schema 变更 / Flyway migration**：per FR-010 零 migration
- **跨模块改动 / mbw-shared 改动**：per FR-010 零跨模块
- **performance regression test (k6)**：M1.3 引入 CI 性能基线（per PRD § 6.2），spec D 不预跑
- **OpenAPI consumer (前端) 同 PR 改动**：本 spec 是 server PR；spec C impl 是 app 仓后续 PR

---

## Open Questions

| #   | 问 | 决议 |
| --- | --- | --- |
| Q-CL-001 | ProblemDetail body 是否暴露 `freezeUntil` 字段 | ✅ **CL-001 (a)** — 暴露 ISO 8601 UTC `freezeUntil` 字段；前端 spec C Q3 选择不消费；为 PHASE 2 留扩展口 |
| Q-CL-002 | FROZEN 分支返 403 时是否消费 SMS code | ✅ **CL-002 (a)** — 保留现有顺序消费码；用户走 cancel-deletion 重发码（独立 `cancel:<phone>` bucket，无 friction） |
| Q-CL-003 | `TimingDefenseExecutor` bypass FROZEN 的实现策略 | ✅ **CL-003 (a)** — `executeInConstantTime` 加可选 `Predicate<Throwable> bypassPad` 参数；caller 显式声明 bypass 类型 |
| Q-CL-004 | `SingleEndpointEnumerationDefenseIT` 修订 vs 新建 | ✅ **CL-004 (a)** — 修订既有 IT 从 4 路径改 3 路径；FROZEN 路径由 `FrozenAccountStatusDisclosureIT` 单独验证 |
| Q-CL-005 | `AccountInFreezePeriodException` javadoc 是否标注 disclosure intent | ✅ **CL-005 (a)** — 加 javadoc 段 `<p><b>Disclosure boundary</b>: ...`；防后续维护者误合并回 InvalidCredentialsException |

---

## References

- **下游契约消费方**：[no-vain-years-app `spec/delete-account-cancel-deletion-ui/spec.md`](https://github.com/xiaocaishen-michael/no-vain-years-app/blob/main/apps/native/spec/delete-account-cancel-deletion-ui/spec.md) § 决策约束 + L13-14 + FR-010 + US4
- **PRD**：[`account-center.v2.md`](../../../../docs/requirement/account-center.v2.md) § 5.4 / § 5.5 / § 7
- **当前实现 baseline**：[`../phone-sms-auth/spec.md`](../phone-sms-auth/spec.md) FR-005 / FR-006 / SC-003 + [`UnifiedPhoneSmsAuthUseCase.java:115-152`](../../../mbw-account/src/main/java/com/mbw/account/application/usecase/UnifiedPhoneSmsAuthUseCase.java) + [`AccountWebExceptionAdvice.java`](../../../mbw-account/src/main/java/com/mbw/account/web/exception/AccountWebExceptionAdvice.java) + [`TimingDefenseExecutor.java`](../../../mbw-account/src/main/java/com/mbw/account/domain/service/TimingDefenseExecutor.java)
- **既有 IT**：[`SingleEndpointEnumerationDefenseIT.java`](../../../mbw-app/src/test/java/com/mbw/app/account/SingleEndpointEnumerationDefenseIT.java) — 本 spec 修订对象
- **状态机**：[`../account-state-machine.md`](../account-state-machine.md) FROZEN / ANONYMIZED transition invariants
- **关联 use case**：[`../delete-account/spec.md`](../delete-account/spec.md)（ACTIVE → FROZEN 触发） / [`../cancel-deletion/spec.md`](../cancel-deletion/spec.md)（FROZEN → ACTIVE 撤销） / [`../anonymize-frozen-accounts/spec.md`](../anonymize-frozen-accounts/spec.md)（FROZEN → ANONYMIZED 定时任务）
- **decision context**：用户 2026-05-07 4 项决策对话（仅 FROZEN 暴露 / 403+ProblemDetail / 新建独立 spec dir + 同 PR 改 phone-sms-auth）
- **Constitution**：[`.specify/memory/constitution.md`](../../../.specify/memory/constitution.md) DDD 5-layer + TDD strict + spec-kit 3 段不自创子层 + spec.md 不重复 OpenAPI 数据契约
- **meta MEMORY**：`reference_server_antienum_drift_vs_prd.md`（2026-05-07 之前已识别此 drift）

---

## 变更记录

- **2026-05-07**：本 spec 首次创建。基于 ① PRD § 5.4 + § 5.5 + § 7 既定语义（ACCOUNT_IN_FREEZE_PERIOD HTTP 403）② 当前 phone-sms-auth `UnifiedPhoneSmsAuthUseCase.doExecute` line 132-134 反枚举吞 FROZEN/ANONYMIZED 共抛 InvalidCredentialsException 与 PRD drift（per meta MEMORY `reference_server_antienum_drift_vs_prd.md`）③ 下游 spec C `delete-account-cancel-deletion-ui` 拦截 modal 设计依赖 server 暴露此错误码 ④ 用户 2026-05-07 4 项决策（仅 FROZEN 暴露 / 403+ProblemDetail / 新建独立 spec dir + 同 PR 改 phone-sms-auth）。spec 阶段产出 5 User Stories + 10 Functional Requirements + 10 Success Criteria + Edge Cases + Open Questions（5 cross-cutting 待 `/speckit.clarify` 决） + Out of Scope（明示 ANONYMIZED disclosure / freeze_until 前端消费 / FROZEN 路径不消费 SMS code 优化均不在 scope）。同 PR 修订 phone-sms-auth/spec.md（FR-005 / FR-006 / SC-003 + 新增 Clarifications CL-006，per FR-008 + SC-005）。
- **2026-05-07 +1**：`/speckit.clarify` round 1 — 5 项 cross-cutting 全部决议。CL-001 暴露 `freezeUntil` ISO 8601（per PRD § 5.5 原文 + 为 PHASE 2 留扩展口）/ CL-002 保留现有 SMS code 消费顺序（FROZEN 用户走 cancel-deletion 重发码无 friction）/ CL-003 `TimingDefenseExecutor.executeInConstantTime` 加可选 `bypassPad: Predicate<Throwable>` 参数（侵入低 + 向后兼容）/ CL-004 修订既有 `SingleEndpointEnumerationDefenseIT` 删 FROZEN case 改 3 路径 + FROZEN 由新 `FrozenAccountStatusDisclosureIT` 单独验证 / CL-005 `AccountInFreezePeriodException` 加 javadoc disclosure 段防后续维护者误合并回 InvalidCredentialsException。同步修订 FR-001（含 javadoc disclosure 段）/ FR-003（ProblemDetail body 加 freezeUntil setProperty）/ FR-004（bypassPad 参数 + lambda spec）/ FR-006（CL-002 落点引用 + UX friction 评估）。Open Questions 5 项全标 ✅ 决议；Phase 1 Doc clarify 收尾。
