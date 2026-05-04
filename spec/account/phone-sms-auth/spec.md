# Feature Specification: Unified Phone-SMS Auth (账号中心 unified login/register use case)

**Feature Branch**: `spec/account-phone-sms-auth`
**Created**: 2026-05-04
**Status**: Draft（pending impl，docs-only PR per [ADR-0016](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md)）
**Module**: `mbw-account`
**Input**: User description: "登录注册合二为一：客户端只输入手机号 + SMS code 一键登录；server 自动判已注册→login / 未注册→自动创建+login。参考大陆主流 app 范式（网易云音乐 / 小红书 / 拼多多）"

> **Supersedes**: 本 spec 取代以下三个 use case 的合并实施（per ADR-0016 决策 1 + 2）：
>
> - `spec/account/register-by-phone/`（M1.1 落地）— 路径 `POST /api/v1/accounts/register-by-phone` 删除
> - `spec/account/login-by-phone-sms/`（M1.1 落地）— 路径 `POST /api/v1/auth/login-by-phone-sms` 删除
> - `spec/account/login-by-password/`（M1.1 落地）— 路径 `POST /api/v1/auth/login-by-password` 删除
>
> 上述三个目录加 `SUPERSEDED.md` 但保留原 spec 历史。
>
> **决策约束**：M1.2 docs-only；前端 spec 重写见 app 仓 [`apps/native/spec/login/`](https://github.com/xiaocaishen-michael/no-vain-years-app/blob/main/apps/native/spec/login/)；mockup 重做由 user 单独跑 Claude Design（per `docs/experience/claude-design-handoff.md` § 2.1b 合一页 prompt 模板）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 主流程：已注册用户登录（Priority: P1）

已注册大陆手机号用户回访场景下，输入「手机号 + SMS code」一气呵成完成登录，得到新的 access/refresh token 立即可用。**用户视角不存在"注册"或"登录"区分**。

**Why this priority**: 主路径，所有已注册用户的回访入口；M1.2 业务下注册路径与登录路径合一。

**Independent Test**: Testcontainers 起 PG + Redis + Mock SMS gateway，预先注册 ACTIVE 账号 → POST `/api/v1/accounts/sms-codes` `{phone}`（无 purpose 字段，per FR-004）→ POST `/api/v1/accounts/phone-sms-auth` `{phone, code}` → 断言 200 + `{accountId, accessToken, refreshToken}` + `Account.lastLoginAt` 更新。

**Acceptance Scenarios**:

1. **Given** ACTIVE 账号 `+8613800138000` 已存在，**When** POST `/api/v1/accounts/sms-codes` `{phone}`，**Then** SMS gateway 收到 Template A（真实验证码）请求；5 分钟内验证码 hash 写入 Redis `sms_code:<phone>`
2. **Given** 验证码 5 分钟内有效，**When** POST `/api/v1/accounts/phone-sms-auth` `{phone, code}`，**Then** 返回 200 + `{accountId, accessToken, refreshToken}`；DB `Account.last_login_at` 更新为当前 UTC
3. **Given** 已注册用户连续登录多次，**When** 各 token 单独鉴权请求，**Then** 所有有效 token 都通过（refresh token revoke 在 Phase 1.3 use case 实施）

---

### User Story 2 - 主流程：未注册用户自动注册+登录（Priority: P1，并列）

未注册大陆手机号用户首次到访，输入「手机号 + SMS code」流程完全相同；server **静默创建** ACTIVE 账号并签 token。**客户端无感知"创建"动作**——返回响应与已注册路径字节级一致。

**Why this priority**: 大陆主流 UX 核心 —— 用户无注册心智负担（per ADR-0016 决策 1）。

**Independent Test**: 未注册号 `+8613900139000`，发起 `/sms-codes` + `/phone-sms-auth` 流程，断言响应**字节级与已注册路径一致**；DB 新增 `Account` 记录 status=ACTIVE，触发 `AccountCreatedEvent` 写 outbox。

**Acceptance Scenarios**:

1. **Given** `+8613900139000` 未在 DB 内，**When** POST `/api/v1/accounts/sms-codes` `{phone}`，**Then** SMS gateway 收到 Template A（真实验证码，与已注册路径一致——see FR-004）；返回 200 OK 字节级与已注册路径同
2. **Given** 验证码有效，**When** POST `/api/v1/accounts/phone-sms-auth` `{phone, code}`，**Then** server transactional 创建 `Account(phone, status=ACTIVE)` + 签 token + outbox 写 `AccountCreatedEvent`；响应 200 + `{accountId, accessToken, refreshToken}` 字节级与已注册路径同
3. **Given** 同一未注册号短时间内重复触发流程（concurrent requests），**When** server 处理，**Then** 仅创建 1 个 Account（DB unique constraint 兜底 + transactional 串行化）；返回相同 accountId

---

### User Story 3 - 异常：FROZEN / ANONYMIZED 账号反枚举（Priority: P1，并列）

注销冻结期账号（FROZEN）或已匿名化账号（ANONYMIZED）尝试登录，系统**不暴露状态信号**——返回与"码错误"完全一致的错误响应（含响应字节 + 时延）。

**Why this priority**: 防枚举安全基线（OWASP ASVS V3.2 / 个保法）；已注销用户重新注册的合规边界。

**Independent Test**: 预设 FROZEN 账号 + ANONYMIZED 账号；发 `/sms-codes` + `/phone-sms-auth` 提交正确码，断言响应与"码错误"场景**字节级 + P95 时延差 ≤ 50ms**。

**Acceptance Scenarios**:

1. **Given** 账号 `+8613800138001` status=FROZEN（注销冻结期）+ 验证码正确，**When** POST `/phone-sms-auth`，**Then** 返回 `INVALID_CREDENTIALS` 错误（HTTP 401，与"码错误"完全一致），**不签 token，不更新 last_login_at，不解除冻结**
2. **Given** 账号 `+8613800138002` status=ANONYMIZED（已匿名化）+ 验证码正确，**When** POST `/phone-sms-auth`，**Then** 同上 `INVALID_CREDENTIALS`；该 phone 由 `/sms-codes` 路径 viewing 为"未注册"（用 User Story 2 自动注册路径**仅当** phone NOT EXISTS in DB；ANONYMIZED account 仍存在但 phone 已 NULL，故 phone 在新 account 创建时不冲突）
3. **Given** 攻击者比较"FROZEN 账号"vs"码错误"vs"未注册自动登录"三种响应，**Then** 时延差 ≤ 50ms（per FR-006 timing defense + dummy bcrypt hash）；status / body / headers 字节级一致（仅"未注册自动登录"返回 200 + token，其他返回 401，但 401 路径间字节级一致）

---

### User Story 4 - 边缘：限流防爆刷（Priority: P2）

恶意用户对同一手机号或同一 IP 高频请求 `/sms-codes` 或 `/phone-sms-auth`，系统按规则限流并返回 HTTP 429 + `Retry-After`，避免短信费用爆炸 + 账号枚举辅助暴破。

**Why this priority**: 复用既有 `RateLimitService` 基础设施（per [ADR-0011](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0011-rate-limit-jcache-then-redis.md)）；`/sms-codes` 60s 限流是反 SMS 滥发硬性合规要求。

**Independent Test**: Testcontainers 测试快速调 `/sms-codes` N 次断言第 2 次起返回 429；24h 内同号 5 次失败码后第 6 次返回 LOCKED（隐藏在 `INVALID_CREDENTIALS` 形态内）。

**Acceptance Scenarios**:

1. **Given** 手机号 60 秒内已请求过 `/sms-codes`，**When** 再次 POST，**Then** 返回 429 + `Retry-After: <秒数>` + body `{code: RATE_LIMITED}`
2. **Given** 同号 24h 内已用错误码尝试 5 次 `/phone-sms-auth`，**When** 第 6 次提交，**Then** 即使码正确也返回 `INVALID_CREDENTIALS`，账号锁 30 分钟
3. **Given** 同 IP 24h 内已请求 50 次 `/sms-codes`（跨手机号），**When** 第 51 次请求，**Then** 返回 429（per `sms:<ip>` bucket）

---

### Edge Cases

- **手机号格式异常**：非 E.164 / 非大陆段（不匹配 `^\+861[3-9]\d{9}$`）→ 返回 `INVALID_PHONE_FORMAT`（HTTP 400）
- **验证码过期**：超过 5 分钟 → 返回 `INVALID_CREDENTIALS`（不区分"过期"vs"错误"）
- **验证码已使用**：同一码二次提交 → 返回 `INVALID_CREDENTIALS`（单次有效）
- **SMS gateway 失败**：Resend (mock) / 阿里云短信 API 超时 / 错误 → 返回 `SMS_SEND_TIMEOUT`（HTTP 503）
- **Token 签发失败**：JWT 签名异常 → 返回 `INTERNAL_SERVER_ERROR`（HTTP 500），未更新 last_login_at / 未创建 Account（事务回滚）；客户端可重新走 auth 流程
- **并发同号自动注册**：DB unique constraint on `account.phone` + `@Transactional(SERIALIZABLE)` 保证仅创建 1 个 Account；duplicated insert 落错处理为"作为已注册路径处理"（fallback to login）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001（Endpoint）**：唯一 endpoint `POST /api/v1/accounts/phone-sms-auth`，入参 `{phone: string, code: string}`，响应 `{accountId, accessToken, refreshToken}` 或 RFC 9457 ProblemDetail 错误。**无 password / email 字段**（per ADR-0016 决策 2 + 3）
- **FR-002（Phone 格式）**：`^\+861[3-9]\d{9}$`（仅大陆段）；不匹配 → `INVALID_PHONE_FORMAT` HTTP 400
- **FR-003（SMS code 存储）**：复用既有 SMS code 基础设施 — Redis `sms_code:<phone>` 5min TTL，hash 存储（不存明文，per CL-002）；与 SMS gateway / RateLimitService 完全复用
- **FR-004（SMS Purpose 隐藏）**：`/api/v1/accounts/sms-codes` endpoint 入参**简化为 `{phone}`**（删除 purpose 字段）；server 内部根据 phone 是否存在动态决定 SMS template：
  - phone 存在 ACTIVE → Template A（真实验证码，文案"登录验证码"）
  - phone 不存在 → Template A（真实验证码，文案与上同——per User Story 2 反枚举一致）
  - phone 存在 FROZEN / ANONYMIZED → Template A（仍发，但 `/phone-sms-auth` 提交正确码会被反枚举吞，per User Story 3）
  - **取消 Template C**（旧 login-by-phone-sms 的"未注册号收登录失败提示"）— 新模式下未注册号路径 = 自动注册成功，无需"登录失败"文案
- **FR-005（核心分支逻辑）**：`/phone-sms-auth` use case 内部按 phone 查 DB 分支：
  - phone 不存在 → **自动创建** `Account(phone, status=ACTIVE, lastLoginAt=now())` + outbox `AccountCreatedEvent` + 签 token → 返回 200
  - phone 存在 + status=ACTIVE → updateLastLoginAt + 签 token → 返回 200
  - phone 存在 + status=FROZEN / ANONYMIZED → 反枚举吞下：dummy bcrypt 计算（timing defense） + 返回 `INVALID_CREDENTIALS` HTTP 401
- **FR-006（反枚举 timing defense）**：成功路径（已注册 ACTIVE / 未注册自动注册）+ 失败路径（FROZEN / ANONYMIZED / 码错 / 码过期）必须**响应 P95 时延差 ≤ 50ms**：
  - 失败路径调用 `TimingDefenseExecutor` 计算 dummy BCrypt hash（cost=12，5-15ms）
  - 复用既有 `TimingDefenseExecutor` 实现（原 register-by-phone use case 引入，DB schema `password_hash` 字段保留作 dummy hash 计算输入）
  - 由独立集成测试 `SingleEndpointEnumerationDefenseIT` 验证 1000 次请求 P95 差 ≤ 50ms
- **FR-007（限流规则，复用 + 新增）**：
  - 复用 `sms:<phone>` 60s 1 次（per RateLimitService 既有规则）
  - 复用 `sms:<phone>` 24h 10 次
  - 复用 `sms:<ip>` 24h 50 次
  - **新增** `auth:<phone>` 24h 5 次失败后锁 30min（独立 bucket，与历史 `register:<phone>` / `login:<phone>` bucket 替换合并）
- **FR-008（事务原子性）**：phone-sms-auth use case **单一事务边界**内完成：
  - **执行顺序**：限流 → 验证码消费（Redis DEL） → 查 Account → 状态分支 → (新建 Account 或 updateLastLoginAt) → outbox event（仅新建路径） → Token 签发 → commit
  - `@Transactional(rollbackFor = Throwable.class)`；并发同号通过 DB unique constraint + serialization isolation 兜底
- **FR-009（响应 token 规格）**：access (JWT, TTL 15min) + refresh (random 256-bit, TTL 30day)，与既有 register / login 两 use case 输出格式一致；JWT secret = `MBW_AUTH_JWT_SECRET`（fail-fast）
  - **Refresh token 持久化**：`RefreshTokenRecord` 在 Phase 1.3 use case 引入；本 use case 仅签发 + 返回 + 不写持久化记录（与历史 register / login 一致行为）
- **FR-010（错误响应格式）**：所有错误响应遵循 RFC 9457 ProblemDetail（`application/problem+json`）；由 `mbw-shared.web.GlobalExceptionHandler` 映射；与既有 use case 一致
- **FR-011（Outbox event）**：自动注册路径 publish `AccountCreatedEvent`（既存事件类，schema 不变）— Spring Modulith Event Publication Registry 持久化到 outbox 表
- **FR-012（删除既有 endpoint）**：M1.2 实施时**一刀切删**：
  - `POST /api/v1/accounts/register-by-phone` + `RegisterByPhoneUseCase` + `RegisterByPhoneCommand` + `RegisterByPhoneResult` + `RegisterByPhoneRequest` + `RegisterByPhoneResponse`
  - `POST /api/v1/auth/login-by-phone-sms` + `LoginByPhoneSmsUseCase` + 相关 DTO
  - `POST /api/v1/auth/login-by-password` + `LoginByPasswordUseCase` + 相关 DTO
  - 对应集成测试 `RegisterByPhoneE2EIT` / `LoginByPhoneSmsE2EIT` / `LoginByPasswordE2EIT` / `CrossUseCaseEnumerationDefenseIT`
  - SMS Template C（`SMS_TEMPLATE_LOGIN_UNREGISTERED`）配置删除（旧"未注册收登录失败提示"）

### Key Entities

- **Account（聚合根）**：复用既有 Account；本 use case 不引入新字段
  - `email` 字段保留 schema 但不写入新值（`[DEPRECATED M1.2 ADR-0016]`，per PRD 修订）
  - `password_hash` 字段保留 schema 但不写入新值（**作 dummy hash 计算输入用**，per FR-006 timing defense）
- **新增**：无（不引入新聚合根 / 实体 / 值对象）
- **删除**：无（domain 层无变化；删的是 application / web / DTO 层）

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**：P1 主流程（User Story 1 + 2）端到端 P95 ≤ **600ms**（不含 SMS gateway 调用，从 `/phone-sms-auth` 入到 200 响应）
- **SC-002**：100 个不同 phone 并发 `/phone-sms-auth` 请求（混合已注册 / 未注册）—— **0 错误，token 数 = 请求数；新建 Account 数 = 未注册请求数**
- **SC-003（核心反枚举）**：`/phone-sms-auth` 对 4 种分支响应（已注册 ACTIVE 成功 / 未注册自动注册成功 / FROZEN 失败 / 码错失败）的 status / body / headers / P95 时延**字节级一致 / 时延差 ≤ 50ms**；由 `SingleEndpointEnumerationDefenseIT` 1000 次请求验证
- **SC-004**：限流准确性 — FR-007 全部 4 条规则集成测试验证生效，错误返回 429 + 正确 `Retry-After`
- **SC-005**：`/sms-codes` 入参不含 purpose 字段（per FR-004）；OpenAPI spec 反映新形态
- **SC-006**：3 个旧 endpoint（`register-by-phone` / `login-by-phone-sms` / `login-by-password`）从 OpenAPI spec 完全消失；前端 `pnpm api:gen` 后旧 API class 自动删除
- **SC-007**：3 个旧 use case + 对应 IT 类完全删除；ArchUnit / Spring Modulith Verifier CI 仍 0 violation

## Clarifications

> 5 点澄清于 2026-05-04 与 ADR-0016 决策同期完成。

### CL-001：FROZEN / ANONYMIZED 账号在新模式下如何反枚举

**Q**：旧模式 register / login 双接口分别处理 phone 状态；新模式单接口下 FROZEN 账号尝试登录 / ANONYMIZED 账号 phone 被新用户尝试时，如何避免暴露状态？

**A**：FR-005 明确分支 + FR-006 timing defense。FROZEN 账号→反枚举吞为"码错"；ANONYMIZED 账号 phone 字段被匿名化为 NULL（per PRD § 5.5），故 ANONYMIZED 账号的"phone"在新 phone-sms-auth 路径下视为"未注册"——可被任意人重新注册（per ADR-0016 决策 1 default 路径），但绑定为新 accountId（不恢复匿名化数据，per PRD § 5.5"不可逆"）。

**落点**：FR-005 显式 3 分支；User Story 3 含 FROZEN / ANONYMIZED 双场景；Edge Cases 不再有"匿名化 phone 重新注册" 段（自然成为 User Story 2 的子场景）。

### CL-002：`/sms-codes` 删 purpose 字段是否破坏 OpenAPI 兼容

**Q**：旧 `/sms-codes` 入参支持 `purpose: "register" | "login"`（per login-by-phone-sms FR-009）；新模式删此字段，前端老版本 client 调用会兼容失败吗？

**A**：M1 阶段无真实用户 + 客户端 + server 同 PR 周期发布 → 不需要 backward compat。OpenAPI spec 直接 breaking change（删 `purpose` 字段）；前端 `pnpm api:gen` 拉新 spec 后 TS 类型自动更新。

**落点**：FR-004 明确删 purpose；Out of Scope 加"backward compat for old clients"。

### CL-003：dummy hash 计算的输入来源

**Q**：旧 register-by-phone FR-013 dummy hash 用 static final 常量 hash；新模式下 FROZEN / ANONYMIZED 账号已存在但 status 非 ACTIVE，是否对其 phone 计算？

**A**：仍用 static final 常量 hash（与既有 `TimingDefenseExecutor` 一致），**不**针对具体 phone / 账号计算（避免引入侧信道）；dummy hash 输入完全静态，仅消耗 CPU 时间。复用既有实现，本 use case 不引入新代码。

**落点**：FR-006 复用 `TimingDefenseExecutor`，无新增。

### CL-004：自动注册路径并发同号

**Q**：未注册 phone 在极短时间内被两个客户端同时提交（如重发 SMS 后 race），server 如何避免双 Account 创建？

**A**：DB `account.phone` partial unique index（per PRD § 2.1）+ 事务 SERIALIZABLE isolation level 兜底；duplicated insert 异常 catch → 回退到已注册路径（视作 User Story 1）。FR-008 显式声明事务边界 + 异常处理。

**落点**：FR-008 + Edge Cases "并发同号"段。

### CL-005：refresh token 持久化沿用 Phase 1.3 计划

**Q**：本 use case 不写 RefreshTokenRecord（与既有 register / login 一致），客户端拿到的 refresh token 此时无服务端 revoke 路径——M1.2 阶段是否引入持久化？

**A**：不。Phase 1.3 use case (`refresh-token`) 引入持久化时**统一回填**本 use case + 既有 register / login（虽然 register / login 旧 use case 在 phone-sms-auth 落地时即被删，但回填指 phone-sms-auth 的 token 签发路径）；M1.2 阶段窗口期内客户端拿 refresh token 等同于"30 天内随便用"（无 revoke 通道）— per ADR-0013 验收范围。

**落点**：FR-009 注 "refresh token 持久化在 Phase 1.3 统一回填"；Out of Scope 加"1.x 内自行管理 RefreshTokenRecord"。

## Assumptions

- **A-001**：复用既有 register-by-phone Assumptions A-001 ~ A-005（SDK / Redis / JWT secret / BCrypt cost / Token TTL）— 仅命名上的 use case 变了，基础设施假设不变
- **A-002**：M1 阶段 v0.x.x 无真实用户；删旧 endpoint + 改 OpenAPI breaking 是可接受的（per CL-002 + ADR-0016 决策 2）
- **A-003**：DB `password_hash` 字段保留 schema 仅作 timing defense dummy hash 输入；M2+ 评估真删该字段
- **A-004**：`AccountCreatedEvent` 既存事件类 schema 不变；新模式自动注册路径复用此事件 publish 到 outbox
- **A-005**：阿里云 SMS Template A 审批已就绪（既有 register-by-phone use case 已审过）；Template C 配置废弃不影响审批资源

## Out of Scope

- **`refresh-token` use case**（token 刷新 + RefreshTokenRecord 持久化）— Phase 1.3 引入
- **`logout-all` use case**（退出所有设备 + revoke RefreshTokenRecord）— Phase 1.4 引入
- **微信 / Google / Apple OAuth** — M1.3 引入（per ADR-0016 决策 4）
- **运营商一键登录 SDK**（中国移动 / 联通 / 电信免密验证）— M2+ 评估（per ADR-0016 决策 5）
- **二维码扫码登录** — M2+ 移动端启用后引入
- **Backward compat for old clients calling `/register-by-phone` / `/login-by-phone-sms` / `/login-by-password`** — per CL-002 + ADR-0016 决策 2 拒绝；M1 v0.x.x 无真实用户
- **DB schema 真删 `email` / `password_hash` 字段** — M2+ 评估（per ADR-0016 Open Questions）
- **1.x 内自行管理 RefreshTokenRecord** — per CL-005 推迟到 Phase 1.3
- **找回密码 / 修改密码** — password 已废，per ADR-0016 决策 2；新模式下"忘记密码"在 UX 中无入口

## References

- [ADR-0016](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0016-unified-mobile-first-auth.md) — 上游决策
- [PRD account-center.v2 § 2.1 / 2.2 / 4.2 / 5.2 / 5.3](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md)（2026-05-04 修订）
- [account-state-machine.md](../account-state-machine.md) § "Auto-create on phone-sms-auth"（同 PR 新增段）
- 历史 spec：[`spec/account/register-by-phone/`](../register-by-phone/) / [`spec/account/login-by-phone-sms/`](../login-by-phone-sms/) / [`spec/account/login-by-password/`](../login-by-password/)（各加 SUPERSEDED.md）
