# Feature Specification: Login by Password (账号中心 P1.2 use case)

**Feature Branch**: `spec/account-login-by-password`
**Created**: 2026-05-02
**Status**: Draft（pending plan + tasks 联动 + analysis 通过）
**Module**: `mbw-account`
**Input**: User description: "已设密码用户通过手机号 + 密码登录，与 login-by-phone-sms (1.1) 形成第二条登录路径"

> 决策约束：M1.2 plan §今日（续 III）已锁定前端 Path B；本 spec 仅约束后端 use case。**与 [login-by-phone-sms](../login-by-phone-sms/spec.md) 大量共享 FR / SC**，本 spec 重点描述差异。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 主流程：已设密码用户成功登录（Priority: P1）

已注册且**已设密码**的大陆手机号用户通过 "手机号 + 密码" 完成登录，得到新的 access/refresh token 立即可用。**比 login-by-phone-sms 体验快**（无需等 SMS 60s 倒计时）。

**Why this priority**: 老用户回访的"快速通道"——若仅有 1.1 短信登录，每次回访都要等 SMS 转链路，体验劣化。

**Independent Test**: 集成测试预先注册 ACTIVE 账号 + PasswordCredential（含 BCrypt hash），调 `POST /api/v1/auth/login-by-password` 提交 phone + password → 断言返回 200 + token + Account.lastLoginAt 更新。

**Acceptance Scenarios**:

1. **Given** ACTIVE 账号 `+8613800138000` 注册时设了密码 `Test123456`，**When** 客户端 POST `/api/v1/auth/login-by-password` `{phone:"+8613800138000", password:"Test123456"}`，**Then** response 返回 200 + `{accountId, accessToken, refreshToken}`，DB account.last_login_at 更新为当前 UTC
2. **Given** 同上，**When** 提交错误密码 `Wrong123`，**Then** 返回 `INVALID_CREDENTIALS`（HTTP 401，与"码错"完全相同形态）
3. **Given** 用户连续登录多次（拿到不同 token），**When** 各 token 单独提交业务请求，**Then** 所有有效 token 都能通过鉴权（无单设备唯一约束 — `logout-all` 1.4 才能批量 revoke）

---

### User Story 2 - 异常：未设密码账号防枚举（Priority: P1，并列）

已注册但**未设密码**（注册时 password 字段为可选，可不提供）的账号尝试密码登录时，系统**不暴露"未设密码"信号** —— 返回与"密码错"完全相同的错误响应，避免攻击者推断账号是否设过密码。

**Why this priority**: 与 register-by-phone User Story 3 / login-by-phone-sms User Story 2 镜像，OWASP ASVS V3.2 / 个保法 隐私基线。攻击者推断"已注册但未设密码"账号 = 推荐其重置密码或诱导社工。

**Independent Test**: 集成测试预先注册 ACTIVE 账号但**不**创建 PasswordCredential，调 `POST /api/v1/auth/login-by-password` → 断言响应与"密码错"场景**字节级相同**（response body / status code / headers / P95 时延）。

**Acceptance Scenarios**:

1. **Given** ACTIVE 账号 `+8613800138001` 注册时未设密码（无 PasswordCredential 行），**When** 提交任意密码尝试登录，**Then** 返回 `INVALID_CREDENTIALS`（HTTP 401，与"密码错"完全相同形态，per FR-005）
2. **Given** 同上，**When** 提交格式不符的密码（如纯数字 `12345678`，违反 FR-003 强度），**Then** **依然返回 `INVALID_CREDENTIALS`**（不返回 `INVALID_PASSWORD`），避免暴露强度信号
3. **Given** 攻击者比较两个手机号（一已设密码一未设）的响应时延，**Then** 时延差不应超过随机抖动范围（通过入口级 BCrypt verify 消除时间侧信道，per FR-009）

---

### User Story 3 - 异常：未注册手机号防枚举（Priority: P1，并列）

恶意用户尝试用未注册手机号触发密码登录，系统**不暴露"未注册"信号** —— 与 1.1 login-by-phone-sms User Story 2 完全对称。

**Why this priority**: 跨 use case 防枚举（SC-005 of 1.1）—— 攻击者不能通过对比 1.1 vs 1.2 vs register 三个 endpoint 的响应推断账号状态。

**Independent Test**: 未注册手机号 `+8613900139000`，调 `POST /api/v1/auth/login-by-password` → 断言响应与"密码错"场景**字节级相同**。

**Acceptance Scenarios**:

1. **Given** `+8613900139000` 未在 DB 内（无 account 记录），**When** POST `/login-by-password` 提供任意密码，**Then** 返回 `INVALID_CREDENTIALS`（HTTP 401，与"密码错"字节级相同形态）
2. **Given** 攻击者跨 endpoint 对比（register 已注册 vs login-by-password 未注册 vs login-by-password 密码错），**Then** 三场景响应字节级一致（per `CrossUseCaseEnumerationDefenseIT`，已在 1.1 引入并扩展覆盖本 use case）

---

### User Story 4 - 边缘：限流防爆破（Priority: P2）

恶意用户对同一手机号高频尝试密码（暴力破解），系统按规则限流并最终账号锁定 30 分钟。

**Why this priority**: 防 brute force 是密码登录的标配安全要求；与 1.1 sms 登录共享 `login:<phone>` 失败 bucket（per CL-001）。

**Independent Test**: 集成测试同 phone 24h 内尝试 5 次错误密码，第 6 次断言返回 `INVALID_CREDENTIALS`（即使密码正确）+ Redis `login:<phone>` 锁定 30 分钟。

**Acceptance Scenarios**:

1. **Given** 同一手机号 24 小时内已用错误密码尝试 5 次 login-by-password，**When** 第 6 次提交（即使正确密码），**Then** 返回 `INVALID_CREDENTIALS`，账号锁 30 分钟
2. **Given** 同上，**When** 在 30 分钟内尝试 1.1 sms 登录（同 phone），**Then** 同样被锁（共享 `login:<phone>` bucket，per CL-001）
3. **Given** 同 IP 24 小时内已请求 50 次密码登录（不同手机号），**When** 第 51 次请求，**Then** 返回 429（per `auth:<ip>` 限流 bucket，新引入，与 sms-codes IP bucket 区分）

---

### Edge Cases

- **手机号格式异常**：非 E.164 / 非大陆段 → 返回 `INVALID_PHONE_FORMAT`（与 1.1 / register 同 FR-001）
- **密码字段缺失**：request body 缺 `password` 字段 → 返回 `INVALID_REQUEST`（HTTP 400，由 Spring Validation 触发）
- **密码格式不符强度**：login 路径**不校验密码强度**（per FR-006），任何字符串均尝试 verify；强度校验仅在 register / set-password
- **账号 FROZEN / ANONYMIZED 状态**：DB 内 status ≠ ACTIVE → 返回 `INVALID_CREDENTIALS`（防枚举，与 1.1 一致）
- **Token 签发失败**：JWT 签名异常 → 返回 `INTERNAL_SERVER_ERROR`，未更新 `last_login_at`
- **PasswordCredential 缺失**（账号注册时未设密码）：返回 `INVALID_CREDENTIALS`（per User Story 2，防枚举）
- **BCrypt verify 异常**（hash 损坏 / cost 不一致）：log ERROR + 返回 `INVALID_CREDENTIALS`（防"系统异常"信号泄漏）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**：手机号格式校验复用 register-by-phone FR-001（`^\+861[3-9]\d{9}$`）
- **FR-002**：login use case 入参 = `{phone, password}`；password 为字符串（**任意长度**，不在 login 路径做强度校验，per FR-006）。响应同 1.1：`{accountId, accessToken, refreshToken}`
- **FR-003**：账号状态机访问规则 —— 仅 `status = ACTIVE` 账号可登录；其他 status → `INVALID_CREDENTIALS`（与 1.1 FR-003 一致）
- **FR-004**：登录成功必须更新 `Account.last_login_at`（与 1.1 FR-004 一致）；DB 写入与 token 签发原子（任一失败回滚）
- **FR-005**：错误响应 `INVALID_CREDENTIALS` 用于所有"密码相关 + 账号不存在 + 未设密码"失败（不区分密码错 / 未注册 / 未设密码 / 状态非 ACTIVE / hash 损坏），HTTP 401。**与 1.1 FR-006 + register-by-phone FR-007 形态字节级一致**（跨 use case 防枚举 SC-005 已覆盖）
- **FR-006**：login 路径**不校验密码强度**——任何 password 字符串均尝试 BCrypt verify，强度不符不返回 `INVALID_PASSWORD`。理由：避免暴露"账号密码强度"信号（攻击者推断弱密码账号）。强度校验仅在 register / set-password 路径
- **FR-007**：限流规则：
  - **复用** 1.1 FR-005 的 `login:<phone>` bucket（24h 5 次失败锁 30 分钟）—— sms 登录失败 + password 登录失败**共享**计数（per CL-001）
  - **新增** `auth:<ip>` 24h 100 次（IP 维度，跨 phone 跨 endpoint /login-by-* 汇总；与 sms-codes IP bucket `sms:<ip>` 区分）
- **FR-008**：登录成功响应 token 同 1.1 FR-007（access JWT TTL 15min + refresh 256-bit TTL 30day）；refresh token 持久化推迟到 Phase 1.3 统一回填
- **FR-009**：login-by-password 接口的**时延侧信道防御** —— **入口级 BCrypt verify**（区别于 1.1 / register 的 dummy bcrypt entry-level）：
  - 入口拦截器收到 password 后**直接执行** `BCryptPasswordEncoder.matches(userPassword, hashFromDB)` 用作时延对齐，**不区分账号是否存在 / 是否设过密码**：
    - 账号存在 + 设过密码：用真实 PasswordCredential.passwordHash
    - 账号存在 + 未设密码 / 账号不存在：用预置 dummy hash（per register-by-phone FR-013 已建的 static final dummy hash）
  - 这样所有路径都执行**完全相同的 BCrypt 计算**（cost=12 同），自然时延一致 → SC-003 集成测试 1000 次对比 P95 时延差 ≤ 50ms
  - **不再需要额外 dummy bcrypt**（与 register 不同——register 是 dummy + 后续可能再跑用户密码 hash，login-by-password 是入口直接跑用户密码 vs hash compare）
- **FR-010**：login-by-password use case 的**原子性约束** —— password verify + DB last_login_at 更新 + Token 签发必须在**单一事务边界**内完成。任一失败回滚全部：
  - `@Transactional(rollbackFor = Throwable.class)`
  - **执行顺序**：限流 → 查 Account + PasswordCredential（连表查）→ 状态校验 → BCrypt verify → Token 签发 → 写 last_login_at → commit
  - 与 1.1 FR-010 同 pattern，仅替换"验证码消费"为"BCrypt verify"
- **FR-011**：所有错误响应必须遵循 RFC 9457 ProblemDetail 格式（与 1.1 FR-008 一致）
- **FR-012**：login-by-password 不发送 SMS（无 sms-codes 步骤）；不依赖 Template C 阿里云审批前置（与 1.1 区别）

### Key Entities

- **Account（聚合根）**：复用 1.1 Account（含 lastLoginAt 字段）
- **PasswordCredential**：register-by-phone Key Entities Credential 之一；存 BCrypt hash + cost=12 + lastUsedAt + createdAt
- **新增**：无（不引入新聚合根 / 实体 / 值对象）

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**：P1 主流程端到端 P95 ≤ **500ms**（不含 BCrypt 计算时间——cost=12 typically 80-150ms 已在入口对齐时延独立计算）
- **SC-002**：100 个不同已设密码账号并发登录（Testcontainers 集成测试），**0 错误，token 数与请求数完全一致**
- **SC-003**：账号枚举安全测试：login-by-password 接口对"已设密码"vs"未设密码"vs"未注册" 三场景的响应字节级相同（status / body / headers / P95 时延差 ≤ 50ms）
- **SC-004**：限流准确性：FR-007 所有 3 条规则在集成测试中验证生效，错误返回 429 + 正确 `Retry-After`
- **SC-005**：跨 use case 防枚举：扩展 1.1 引入的 `CrossUseCaseEnumerationDefenseIT`，覆盖 register / login-by-phone-sms / login-by-password 三个 endpoint 在已注册 vs 未注册 vs 错凭据三场景下响应一致

## Clarifications

> 2 点澄清于 2026-05-02 完成。

### CL-001：sms 登录失败与 password 登录失败是否共享 login:`<phone>` bucket

**Q**：1.1 的 `login:<phone>` 24h 5 次失败锁 bucket 是否与 1.2 的密码错共享计数？

**A**：**共享**。理由：

- 都是"登录"的失败次数，业务语义一致
- **避免绕过**：若独立 bucket，攻击者用 5 次错码（消耗 sms 计数）+ 5 次错密码（消耗 password 计数）共 10 次绕过单 bucket 5 次锁
- Redis key 统一 `login:<phone>`，counter increment by 1 each fail（不区分 sms / password）
- 锁定后两个 endpoint 都返 `INVALID_CREDENTIALS`（不区分"被锁"vs"凭据错"）

**落点**：FR-007 显式标注共享 bucket 与 1.1 FR-005 联动；implement 时核对 RateLimitService key 一致

### CL-002：入口级 BCrypt verify 复用 register 的 dummy bcrypt 入口拦截器吗？

**Q**：login-by-password 入口需要执行 BCrypt verify 用作时延对齐 + 真实业务校验。是否复用 register 已建的 `TimingDefenseExecutor` + dummy hash？

**A**：**不直接复用，但共享 dummy hash + 类似 wrapper 模式**。理由：

- register 入口跑的是 **dummy bcrypt with garbage password**（用户没提供 password）→ 纯时延对齐
- login-by-password 入口跑的是 **真实 BCrypt verify(userPassword, hashFromDB)** → 业务校验 + 时延对齐二合一
- 共享：static final `DUMMY_HASH`（register 已建）—— login-by-password 在账号不存在 / 未设密码场景用此 hash 占位，让 verify 计算时延一致
- 不共享：执行 wrapper 不同——TimingDefenseExecutor 是 register/login-sms 的，login-by-password 需要新建 `PasswordVerifyTimingDefenseExecutor` 或扩展现有为通用版

**落点**：plan.md § Time Defense 段决策（推荐扩展 `TimingDefenseExecutor` 为通用接口，加 `executeWithBCrypt(callable, hashLookup)` 变体）；implement 时核对

## Assumptions

- **A-001**：复用 register-by-phone Assumptions A-001 ~ A-005 + 1.1 A-006 ~ A-007
- **A-008**：account 表已有 password_hash 列在 credential 表（per V2 migration register-by-phone 已落）；本 use case **不引入新 schema**
- **A-009**：BCrypt cost = 12 在所有路径（register / login-by-password / dummy hash）一致；启动时 cost 配置不变（M2+ 评估升 cost 走独立 use case）

## Out of Scope

- **`set-password` use case**（登录后补设密码 / 改密码）— M1.3 引入
- **`forgot-password` use case**（忘记密码 + 短信重置）— M1.3 引入
- **登录成功后强制改密码** — 无业务需求，不引入
- **多因素认证 / 2FA** — M3+ 内测后视需求
- **密码强度 zxcvbn 检查** — register 路径已用 `PasswordPolicy` 静态规则，zxcvbn 动态评分 M2+ 评估
- **失败次数 metric / SIEM 告警** — M3+ 引入观测后再加
- **refresh-token + RefreshTokenRecord 持久化** — Phase 1.3 引入
- **login 失败 captcha 触发** — M1.3 引入 Cloudflare Turnstile 后再加
