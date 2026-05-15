# Feature Specification: Login by Phone SMS (账号中心 P1.2 use case)

**Feature Branch**: `spec/account-login-by-phone-sms`
**Created**: 2026-05-02
**Status**: Draft（pending plan + tasks 联动 + analysis 通过）
**Module**: `mbw-account`
**Input**: User description: "已注册用户通过手机号 + 短信验证码登录，复用注册路径基础设施，与 register-by-phone 形成镜像（防枚举方向相反）"

> 决策约束：M1.2 plan §今日（续 III）已锁定前端 Path B（Claude Design 主导 mockup）；本 spec 仅约束后端 use case，前端实施 plan 见 [meta plan file](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/plans/sdd-github-spec-kit-https-github-com-gi-drifting-rossum.md) Phase 4。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 主流程：已注册用户成功登录（Priority: P1）

已注册大陆手机号用户回访场景下，通过 "获取验证码 → 输入码" 完成登录，得到新的 access/refresh token 立即可用。

**Why this priority**: 注册即激活后唯一的回访路径；无此路径首次登录后 access token 过期 = 用户死锁（详见 M1.2 plan 触发因素）。

**Independent Test**: 集成测试用 Testcontainers 起 PG + Redis + Mock SMS gateway，预先注册 ACTIVE 账号 → 调 `POST /api/v1/auth/sms-codes` purpose=login → `POST /api/v1/auth/login-by-phone-sms` → 断言返回 200 + access/refresh token + token 可解码 + Account.lastLoginAt 已更新。

**Acceptance Scenarios**:

1. **Given** ACTIVE 账号 `+8613800138000` 已存在，**When** 客户端 POST `/api/v1/auth/sms-codes` `{phone:"+8613800138000", purpose:"login"}`，**Then** SMS gateway 收到 Template A（真实验证码）请求且 5 分钟内验证码 hash 写入 Redis（与 register 共享同一 `sms_code:<phone>` key）
2. **Given** 验证码 5 分钟内有效，**When** POST `/api/v1/auth/login-by-phone-sms` `{phone, code}`，**Then** response 返回 200 + `{accountId, accessToken, refreshToken}`，DB account.last_login_at 更新为当前 UTC
3. **Given** 用户连续登录多次（拿到不同 token），**When** 各 token 单独提交业务请求，**Then** 所有有效 token 都能通过鉴权（无单设备唯一约束 — `logout-all` use case 才能批量 revoke，详 1.4）

---

### User Story 2 - 异常：未注册手机号防枚举（Priority: P1，并列）

恶意用户尝试用未注册手机号触发登录，系统**不暴露"未注册"信号** —— 返回与"码错误"完全相同的错误响应，避免账号枚举攻击（与 register-by-phone User Story 3 镜像，防枚举方向相反）。

**Why this priority**: 同 register-by-phone User Story 3，OWASP ASVS V3.2 / 个保法 隐私基线。

**Independent Test**: 未注册手机号 `+8613900139000`，发起 `/sms-codes` purpose=login + `/login-by-phone-sms` 流程，断言响应与"码错误"场景**字节级相同**（response body / status code / headers / P95 时延）。

**Acceptance Scenarios**:

1. **Given** `+8613900139000` 未在 DB 内（无 account 记录），**When** 客户端 POST `/api/v1/auth/sms-codes` `{phone, purpose:"login"}`，**Then** 返回 200 OK（与已注册手机号字节级一致）；**后台 SMS gateway 收到 Template C 请求**（登录失败提示文案，**不发送真实验证码**，详 FR-009）
2. **Given** 同上，**When** POST `/login-by-phone-sms` 提供任意码，**Then** 返回 `INVALID_CREDENTIALS` 错误（HTTP 401，与"码错误"完全相同形态，per FR-006）
3. **Given** 攻击者比较两个手机号（一注册一未注册）的 `/login-by-phone-sms` 响应时延，**Then** 时延差不应超过随机抖动范围（通过 dummy bcrypt 入口级 + SMS gateway 一致调用消除时间侧信道，per FR-011）

---

### User Story 3 - 边缘：限流防爆刷（Priority: P2）

恶意用户对同一手机号或同一 IP 高频请求验证码 / 登录接口，系统按规则限流并返回 HTTP 429 + `Retry-After`，避免短信费用爆炸 + 账号枚举辅助暴破。

**Why this priority**: 与 register-by-phone User Story 2 同因，**复用现有 `RateLimitService` 基础设施**（per CL-002）。

**Independent Test**: 集成测试快速调 SMS code 接口（purpose=login）N 次，断言第 2 次起返回 429 + `Retry-After` header；账号 login 接口 24h 内同手机号 5 次失败码后第 6 次返回 LOCKED（隐藏在 INVALID_CREDENTIALS 形态内）。

**Acceptance Scenarios**:

1. **Given** 手机号 `+8613800138000` 60 秒内已请求过 login 验证码，**When** 再次 POST `/sms-codes` purpose=login，**Then** 返回 429 + `Retry-After: <剩余秒数>`，Body 含 `RATE_LIMITED` 错误码
2. **Given** 同手机号 24 小时内已用错误码尝试 5 次 login，**When** 第 6 次提交，**Then** 即使码正确也返回 `INVALID_CREDENTIALS`，账号锁 30 分钟（不返回"已锁"以避免账号枚举）
3. **Given** 同 IP 24 小时内已请求 50 次 SMS code（不同手机号 + 跨 register/login purpose），**When** 第 51 次请求，**Then** 返回 429（per `sms:<ip>` 限流 bucket 与 register 共享）

---

### Edge Cases

- **手机号格式异常**：非 E.164 / 非大陆段（`^\+861[3-9]\d{9}$`）→ 返回 `INVALID_PHONE_FORMAT`（与 register 同 FR-001）
- **验证码过期**：超过 5 分钟使用 → 返回 `INVALID_CREDENTIALS`（不区分"过期"vs"错误"，per FR-006）
- **验证码已使用**：同一码二次提交 → 返回 `INVALID_CREDENTIALS`（单次有效）
- **账号 FROZEN / ANONYMIZED 状态**：DB 内 status ≠ ACTIVE → 返回 `INVALID_CREDENTIALS`（与"未注册"字节级一致；FROZEN / ANONYMIZED 引入在后续 use case，M1.2 仅 ACTIVE）
- **SMS gateway 失败**：阿里云短信 API 超时 / 错误 → 返回 `SMS_SEND_FAILED`（HTTP 503），客户端可重试（受限流约束）
- **Token 签发失败**：JWT 签名异常 → 返回 `INTERNAL_SERVER_ERROR`，未更新 `last_login_at`，客户端重新走 login 流程

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**：手机号格式 + 验证码格式 + Redis 验证码存储与 register-by-phone 完全一致（详 register-by-phone spec.md FR-001 / FR-002）；本 use case **不引入新存储**，`sms_code:<phone>` key 与 register 共享
- **FR-002**：login use case 入参 = `{phone, code}`；**无密码字段**（密码登录走独立的 `login-by-password` use case，1.2）。响应同 register：`{accountId, accessToken, refreshToken}`
- **FR-003**：账号状态机访问规则 —— 仅 `status = ACTIVE` 账号可登录；其他 status（FROZEN / ANONYMIZED / 等未来扩展态）→ 返回 `INVALID_CREDENTIALS`（不暴露 status，per 防枚举心智）
- **FR-004**：登录成功必须更新 `Account.last_login_at` 为当前 UTC 时间戳；DB 写入与 token 签发原子（任一失败回滚，per FR-010）
- **FR-005**：限流规则 **复用 register-by-phone FR-006**（`sms:<phone>` 60s 1 次 / `sms:<phone>` 24h 10 次 / `sms:<ip>` 24h 50 次），**新增** login 专属：
  - `login:<phone>` 24 小时 5 次失败后锁 30 分钟（**与 register 锁 bucket 独立**，避免登录失败连带影响注册尝试）
- **FR-006**：错误响应 `INVALID_CREDENTIALS` 用于所有"码相关 + 账号不存在"失败（不区分错码 / 过期 / 已作废 / 未注册 / 非 ACTIVE 状态），HTTP 401。**与 register-by-phone FR-007 形态字节级一致**，确保跨 use case 攻击者无法枚举（即不能通过对比 register vs login 响应推断账号状态）
- **FR-007**：登录成功响应 token 与 register 同 (FR-008)：access (JWT, TTL 15min) + refresh (random 256-bit, TTL 30day)；JWT secret 沿用 `MBW_AUTH_JWT_SECRET`（fail-fast，per register CL-004）
  - **Refresh token 持久化**：M1.2 阶段**不在本 use case 实施**；refresh-token use case (Phase 1.3) 引入 `RefreshTokenRecord` 持久化时**统一回填** register / login-by-phone-sms / login-by-password 三个 issuer 的写 RefreshTokenRecord 行为，详 [Phase 1.3 plan]
- **FR-008**：所有错误响应必须遵循 RFC 9457 ProblemDetail 格式（`application/problem+json`），由 `mbw-shared.web.GlobalExceptionHandler` 映射；与 register 一致
- **FR-009**：未注册手机号的 **alternate SMS template C**（**M1.2 新引入**）—— `/sms-codes` 处理 purpose=login 的未注册 phone 时：
  - HTTP 响应：200 OK，与已注册手机号字节级一致（per User Story 2 AS 1）
  - 后台行为：调用 SMS gateway 发送 **Template C**（登录失败提示，**不发送真实验证码**），文案示例（具体经阿里云模板审批）：「您正在尝试登录未注册的账号。请先完成注册。」
  - Template C 与 Template A 共享 `sms:<phone>` 限流 bucket 与 SMS gateway 调用路径，保证响应时延一致
  - 阿里云短信模板 ID：`SMS_TEMPLATE_LOGIN_UNREGISTERED`（新增配置项）
  - **实施前置**：Template C 需阿里云模板审批（1-2 工作日）；未审下来时未注册 phone 路径**临时不发任何 SMS**（保留代码占位 + TODO，审下来后回填，与 register Template B 同 pattern per FR-012 of register）
  - **purpose 字段引入**：`/sms-codes` endpoint 必须扩展接受 `purpose: "register" | "login"`（默认 "register" 向后兼容 register-by-phone，不破坏既有契约）；purpose 决定模板分发路径
- **FR-010**：login use case 的**原子性约束** —— 验证码消费 + DB last_login_at 更新 + Token 签发必须在**单一事务边界**内完成。任一失败回滚全部：
  - `@Transactional(rollbackFor = Throwable.class)`
  - **执行顺序**：限流 → 验证码消费 → 查 Account → 状态校验 → Token 签发 → 写 last_login_at → commit
  - 与 register FR-011 同 pattern，但**无 Account / Credential 创建**（仅更新 last_login_at）
- **FR-011**：login 接口的**时延侧信道防御** —— 入口级 dummy bcrypt 计算与 register-by-phone FR-013 共享 static final hash + 入口拦截器；**SC-004 集成测试 1000 次对比 P95 时延差 ≤ 50ms**（已注册 vs 未注册）
  - dummy bcrypt 入口拦截器**复用** register 的 `TimingDefenseExecutor`（domain layer），login 入口注入相同拦截器；具体复用方式见 plan.md
- **FR-012**：Account.last_login_at 列**新增**到 `account.account` schema：`last_login_at TIMESTAMP WITH TIME ZONE NULL`（首次登录前为 NULL；register 创建时为 NULL，首次 login 时更新）
  - **Schema 变更**：本 use case **expand-only**（仅加列，无删旧列），不触发 expand-migrate-contract 三步法
  - Flyway migration: `V3__add_account_last_login_at.sql`
  - 既有 register-by-phone 写入 Account 时**不需要**显式设 last_login_at（NULL 即代表"已注册但未登录"语义）

### Key Entities

- **Account（聚合根）**：复用 register-by-phone Key Entities Account；本 use case **新增** `lastLoginAt: Instant?` 字段
- **VerificationCode**：完全复用 register-by-phone Key Entities VerificationCode，无变化
- **新增**：无（不引入新聚合根 / 实体 / 值对象）

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**：P1 主流程端到端 P95 ≤ **600ms**（不含 SMS gateway 调用时间，从 `/login-by-phone-sms` 入到 200 响应；比 register 800ms 紧 200ms 因为不写 Account / Credential 行）
- **SC-002**：100 个不同已注册账号并发登录（Testcontainers 集成测试），**0 错误，token 数与请求数完全一致**
- **SC-003**：账号枚举安全测试：login 接口对"已注册"vs"未注册"手机号的响应（status / body / headers / P95 时延）字节级相同 / 时延差 ≤ 50ms
- **SC-004**：限流准确性：FR-005 所有 4 条规则在集成测试中验证生效，错误返回 429 + 正确 `Retry-After`
- **SC-005**：跨 use case 防枚举：register 接口与 login 接口对"已注册"vs"未注册"手机号的响应**形态一致**（攻击者不能用一个 endpoint 推另一个的状态）；SC-005 由独立集成测试 `CrossUseCaseEnumerationDefenseIT` 验证（**新增**）

## Clarifications

> 4 点澄清于 2026-05-02 完成。决策已落到对应 FR / Edge Cases / Out of Scope。

### CL-001：是否需 Template C（未注册号收 SMS）

**Q**：FR-009 未注册号收 Template C 提示文案 vs 完全不发 SMS？后者节省成本但时延差异大（SMS gateway 调用 ~500ms-2s）。

**A**：**Template C，发送**。理由：① 时延一致性是 SC-003 防枚举核心；② 文案有正向引导价值（提示用户去注册）；③ Template B（register 已注册号）已立先例，对称设计；④ 成本：未注册号收 login SMS 是稀有事件（攻击者比例 + 用户输错），月成本 < 10 元可控。

**落点**：FR-009 落 Template C；Out of Scope 排除 "未注册号收 0 SMS" 方案；阿里云模板审批一并启动。

### CL-002：限流 bucket 与 register 共享 vs 独立

**Q**：`login:<phone>` 失败计数与 `register:<phone>` 失败计数共享 bucket 吗？

**A**：**独立**（FR-005）。理由：① 用户场景不同——已注册用户输错登录码不应连累注册（已注册用户不会再注册）；② 攻击者诱导问题——若共享，攻击者用 5 次错码消耗 register bucket 后真实未注册用户被锁注册；③ Redis key 显式分离 `register:<phone>` vs `login:<phone>`；④ 共享 bucket = `sms:<phone>` (60s/24h) 与 `sms:<ip>` (24h)，仍然适用（短信请求层面）。

**落点**：FR-005 显式 4+1 条；与 register CL-003（Redis backend 一致）协同。

### CL-003：refresh token 持久化在哪个 use case 引入

**Q**：login 签 refresh token 但不写 RefreshTokenRecord（即 Phase 1.3 才落）会造成"refresh token 无效"窗口吗？

**A**：**否**。理由：① M1.2 时间点 refresh token = 客户端单独保管的随机字符串 + JWT 签名验证（无服务端 revoke 路径）；② Phase 1.3 引入 `/auth/refresh-token` endpoint 时同步写 RefreshTokenRecord 持久化；本 use case (1.1) 仅签 + 返回，未来与 1.3 一并打通服务端验证；③ 1.1 → 1.3 中间客户端使用 refresh token 调任何 endpoint 都会被 401（因为没 refresh-token endpoint），不形成功能漏洞；④ M1.2 plan 接受 1.1-1.3 之间的窗口期。

**落点**：FR-007 加 "refresh token 持久化在 Phase 1.3 统一回填" 段；Out of Scope 排除"1.1 内自行管理 RefreshTokenRecord"。

### CL-004：account.last_login_at 是否走 expand-migrate-contract

**Q**：FR-012 加 `last_login_at` 列是否需要 expand-migrate-contract 三步？

**A**：**否，直接 expand**。理由：① 仅加列（无删旧列 / 改类型 / 改名）→ 无破坏性；② 旧代码（register-by-phone 写 Account）不写 last_login_at = NULL，既有 read 端不引用此列，无兼容性风险；③ M1.2 阶段无真实用户数据（per CLAUDE.md "expand-migrate-contract 跳步"条件 1）；④ Flyway migration `V3__add_account_last_login_at.sql` 单 PR 落地。

**落点**：FR-012 显式标注 expand-only；Constitution Check 段标 "expand-migrate-contract 不适用（纯增列）"。

## Assumptions

- **A-001**：复用 register-by-phone Assumptions A-001 ~ A-005（SDK / Redis / JWT secret / BCrypt cost / Token TTL）
- **A-006**：阿里云 SMS Template C 模板审批可在 Phase 1.4（logout-all）实施前完成；M1.2 PR cycle 起步阶段允许"未审下来时未注册 phone 不发 SMS + 时延 pad to 已注册路径平均时延 ± 50ms" 临时方案
- **A-007**：account.last_login_at 引入不影响既有 register-by-phone 集成测试（register 期望 last_login_at = NULL；login 期望非 NULL）

## Out of Scope

- **`login-by-password` use case**（密码登录）— 独立 use case，1.2 引入
- **`refresh-token` use case**（token 刷新 + RefreshTokenRecord 持久化）— 独立 use case，1.3 引入
- **`logout-all` use case**（退出所有设备 + revoke RefreshTokenRecord）— 1.4 引入
- **微信 / Google / 微博 OAuth 登录** — M1.3 引入
- **二维码扫码登录** — M2+ 移动端启用后引入
- **多设备会话管理 / kick out other sessions on password change** — M3+ 引入
- **登录态续签 / sliding expiration** — refresh token rotation in Phase 1.3 已覆盖；额外 sliding expiration M3+ 评估
- **未注册号收 0 SMS 方案**（节省成本但时延差异大） — per CL-001 拒绝
- **1.1 内自行管理 RefreshTokenRecord** — per CL-003 推迟到 Phase 1.3
