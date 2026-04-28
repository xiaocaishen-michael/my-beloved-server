# Feature Specification: Register by Phone (账号中心首批用例 P1)

**Feature Branch**: `docs/account-register-by-phone-spec`
**Created**: 2026-04-28
**Status**: Draft（待 /speckit.clarify 5 问澄清后转 Ready）
**Module**: `mbw-account`
**Input**: User description: "用户通过手机号 + 短信验证码注册账号，注册即激活，密码可选"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 主流程：未注册手机号成功注册（Priority: P1）

未注册大陆手机号用户通过"获取验证码 → 输入码 → 完成注册"完成账号创建。账号注册即激活（无 PENDING_VERIFY 中间态），返回 access/refresh token 立即可用。

**Why this priority**: 账号中心 MVP 路径，无此路径系统对终端用户零价值。

**Independent Test**: 集成测试用 Testcontainers 起 PG + Redis + Mock SMS gateway，调 `POST /api/v1/accounts/sms-codes` → `POST /api/v1/accounts/register-by-phone` 端到端，断言 DB 出现 ACTIVE account + token 可解码。

**Acceptance Scenarios**:

1. **Given** 手机号 `+8613800138000` 未注册，**When** 客户端 POST `/api/v1/accounts/sms-codes` `{phone:"+8613800138000"}`，**Then** SMS gateway 收到验证码请求且 5 分钟内验证码 hash 写入 Redis
2. **Given** 验证码 5 分钟内有效，**When** POST `/api/v1/accounts/register-by-phone` `{phone, code, password?}`，**Then** account.account 表插入 ACTIVE 行，response 返回 200 + access/refresh token
3. **Given** 用户未提供密码，**When** 注册成功，**Then** account 状态 ACTIVE 但无 password credential 记录；后续仅可短信验证码登录

---

### User Story 2 - 边缘：限流防爆刷（Priority: P2）

恶意用户对同一手机号或同一 IP 高频请求验证码 / 注册接口，系统按规则限流并返回 HTTP 429 + `Retry-After`，避免短信费用爆炸 + 账号枚举。

**Why this priority**: P1 路径若无限流，上线即被刷光短信余额；阻塞性次于 P1 但**业务上必备**。

**Independent Test**: 集成测试快速调 SMS code 接口 N 次，断言第 2 次起返回 429 + Retry-After header；账号注册接口 24h 内同手机号 5 次失败码后第 6 次返回 LOCKED 错误。

**Acceptance Scenarios**:

1. **Given** 手机号 `+8613800138000` 60 秒内已请求过验证码，**When** 再次 POST `/sms-codes`，**Then** 返回 429 + `Retry-After: <剩余秒数>`，Body 含 `RATE_LIMITED` 错误码
2. **Given** 同一手机号 24 小时内已用错误码尝试 5 次，**When** 第 6 次提交，**Then** 即使码正确也返回 `INVALID_CREDENTIALS`，账号锁 30 分钟（不返回"已锁"以避免账号枚举）
3. **Given** 同一 IP 24 小时内已请求 50 次 SMS code（不同手机号），**When** 第 51 次请求，**Then** 返回 429

---

### User Story 3 - 异常：已注册手机号防枚举（Priority: P3）

恶意用户尝试用已注册手机号触发注册，系统**不暴露"已注册"信号**——返回与"码错误"完全相同的错误响应，避免账号枚举攻击。

**Why this priority**: 隐私 + 合规要求（参考 OWASP ASVS V3.2 / 个保法）。功能上不阻塞 P1，但**安全基线**。

**Independent Test**: 已存在 ACTIVE 账号 `+8613800138000`，再次发起 `/sms-codes` + `/register-by-phone` 流程，断言响应与"码错误"场景**字节级相同**（response body / status code / headers）。

**Acceptance Scenarios**:

1. **Given** `+8613800138000` 已是 ACTIVE 账号，**When** 客户端 POST `/sms-codes` `{phone:"+8613800138000"}`，**Then** 返回 200 OK（与未注册手机号字节级一致）；**后台 SMS gateway 收到 Template B 请求**（注册冲突提示，不发真实验证码——见 FR-012）
2. **Given** 同上，**When** POST `/register-by-phone` 提供任意码 + 密码，**Then** 返回 `INVALID_CREDENTIALS` 错误（与"码错误"完全相同形态，per FR-007）
3. **Given** 同上，**When** 攻击者比较两个手机号（一注册一未注册）的响应时延，**Then** 时延差不应超过随机抖动范围（通过 dummy bcrypt 等手段消除时间侧信道）

---

### Edge Cases

- **手机号格式异常**：非 E.164 / 非大陆段（如 `+11234567890`）→ 返回 `INVALID_PHONE_FORMAT`
- **验证码过期**：超过 5 分钟使用 → 返回 `INVALID_CREDENTIALS`（不区分"过期"vs"错误"）
- **验证码已使用**：同一码二次提交 → 返回 `INVALID_CREDENTIALS`（单次有效）
- **DB 唯一约束竞态**：两个并发请求同时 insert 同一手机号 → 后一个捕获 `DataIntegrityViolation` 转 `INVALID_CREDENTIALS`；保证只有 1 个 ACTIVE
- **SMS gateway 失败**：阿里云短信 API 超时 / 错误 → 返回 `SMS_SEND_FAILED`（HTTP 503），客户端可重试（受限流约束）
- **密码格式不符**：未达 FR-003 强度（< 8 字符 / 缺大小写 / 缺数字）→ 返回 `INVALID_PASSWORD`
- **Token 签发失败**：JWT 签名异常 → 返回 `INTERNAL_SERVER_ERROR`，账号已写但 token 未发，客户端重新走登录路径（spec 范围外）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**：手机号格式必须符合 E.164，**M1 仅接受大陆段** `^\+861[3-9]\d{9}$`；其他段返回 `INVALID_PHONE_FORMAT`
- **FR-002**：验证码必须为 6 位数字，TTL **5 分钟**，单次有效。Redis 存储结构 `sms_code:<phone> → { codeHash, attemptCount, maxAttempts=3, createdAt }`：
  - 每次验证失败 `attemptCount++`；达到 `maxAttempts` **立即删除**记录（用户必须重新请求新码，新码再过 SMS 限流）
  - 验证成功立即删除（保持单次有效）
  - 所有失败统一返回 `INVALID_CREDENTIALS`（不区分错码 / 过期 / 已作废，per FR-007）
  - 验证码模板（**Template A**）仅在 phone **未注册**时使用；已注册 phone 走 Template B（见 FR-012）
- **FR-003**：密码**可选**；若提供则必须 ≥ 8 字符 + 至少 1 大写 + 1 小写 + 1 数字；不符合返回 `INVALID_PASSWORD`
- **FR-004**：账号状态机仅 `(无) → ACTIVE`；**无 PENDING_VERIFY 中间态**；DB 写入即 ACTIVE
- **FR-005**：(country_code, phone_number) 全局唯一（DB 复合唯一索引）；ACTIVE 账号必须有至少 1 个 credential（短信码触发的注册视为 phone credential，记录最近一次成功注册时间）
- **FR-006**：限流按以下规则（基于 mbw-shared.RateLimitService，key 格式 `<scenario>:<subject>`）：
  - `sms:<phone>` 60 秒 1 次（每手机号）
  - `sms:<phone>` 24 小时 10 次（每手机号）
  - `sms:<ip>` 24 小时 50 次（每 IP，跨手机号汇总）
  - `register:<phone>` 24 小时 5 次失败后锁 30 分钟（防爆破码）
- **FR-007**：错误响应统一为 `INVALID_CREDENTIALS` 用于所有"码相关"失败（不区分错码 / 过期 / 已作废 / 未发码 / 已注册），避免账号枚举；HTTP 401。**Retry 行为**：重复提交（同 phone，已注册，无 Idempotency-Key）→ DB 唯一约束 → 返回 `INVALID_CREDENTIALS`（与码错误同形态）。客户端应实现"码错→请求新码"流程避免无限重试卡死；Idempotency-Key header 不在 M1.1 范围（M3 引入，详见 [`init-conventions-audit.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/todo/init-conventions-audit.md) P2 段）
- **FR-008**：注册成功响应必须返回 access token (JWT, TTL 15min) + refresh token (随机 256-bit, TTL 30day)；token 签发失败时账号写入回滚
- **FR-009**：SMS gateway 调用必须**异步重试 + 熔断**：对阿里云短信 API 限流码 / 临时故障最多重试 2 次，超过则返回 `SMS_SEND_FAILED`；所有 SMS 错误必须落 SLF4J ERROR 级日志含 requestId
- **FR-010**：所有错误响应必须遵循 RFC 9457 ProblemDetail 格式（`application/problem+json`），由 `mbw-shared.web.GlobalExceptionHandler` 映射

- **FR-011**：注册 use case 的**原子性约束**——Account 写入 + Credential 写入 + Token 签发必须在**单一事务边界**内完成。任一失败回滚全部：
  - 实现使用 `@Transactional(rollbackFor = Throwable.class)`，显式覆盖 unchecked exception
  - **执行顺序**：限流 → 验证码消费 → Token 签发 → DB 写入 → commit。Token 必须**先签发后写 DB**，避免"DB 写入成功但 Token 失败回滚"窗口期间其他请求误判已注册
  - DB 唯一约束（FR-005）+ 应用层捕获 `DataIntegrityViolation` 并转 `INVALID_CREDENTIALS` 共同保证 SC-003

- **FR-012**：已注册手机号的 **alternate SMS template**——`/sms-codes` 处理已注册 phone 时：
  - HTTP 响应：200 OK，与未注册手机号字节级一致（per User Story 3 AS 1）
  - 后台行为：调用 SMS gateway 发送 **Template B**（注册冲突提示，**不发送真实验证码**）
  - Template B 文案示例（具体经阿里云模板审批）：「您正在尝试注册已绑定手机号的账号。如非本人操作请忽略；如忘记密码，可前往登录页使用短信验证码登录。」
  - Template B 与 Template A 共享 `sms:<phone>` 限流 bucket，保证响应时延一致
  - 阿里云短信模板 ID 通过配置文件管理（`SMS_TEMPLATE_VERIFY_CODE` / `SMS_TEMPLATE_REGISTERED_NOTIFY`）
  - **实施前置**：Template B 需阿里云模板审批（1-2 工作日），T6 task `RequestSmsCodeUseCase` 实施前必须先送审；未审下来时已注册 phone 路径**临时不发任何 SMS**（保留代码占位 + TODO，审下来后回填）

### Key Entities

- **Account（聚合根）**：账号身份。属性：账号 ID（雪花或自增 BIGINT）/ phone (E.164) / state (ACTIVE) / createdAt (UTC) / lastLoginAt
- **Credential**：登录凭据，与 Account 1:N。类型：`PhoneCredential`（必有）/ `PasswordCredential`（可选，BCrypt hash）
- **VerificationCode**：验证码记录（Redis 内存对象）。属性：phone / codeHash（BCrypt cost=8）/ **attemptCount**（初始 0，每次错码 +1）/ **maxAttempts**（默认 3，配置项可调）/ TTL（5 min）/ createdAt（UTC）

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**：P1 主流程端到端 P95 ≤ **800ms**（不含 SMS gateway 调用时间，从 `/register-by-phone` 入到 200 响应）
- **SC-002**：100 个不同手机号并发注册（Testcontainers 集成测试），**0 错误，账号数与请求数完全一致**
- **SC-003**：同一手机号竞态注册（10 并发同 phone）测试中**仅产生 1 个 ACTIVE 账号**，DB 唯一约束 + 应用层捕获均不引入数据不一致
- **SC-004**：账号枚举安全测试：注册接口对"已注册"vs"未注册"手机号的响应（status / body / headers / P95 时延）字节级相同 / 时延差 ≤ 50ms
- **SC-005**：限流准确性：FR-006 所有 4 条规则在集成测试中验证生效，错误返回 429 + 正确 `Retry-After`

## Clarifications *(待 /speckit.clarify 填充，下面是初步识别的待澄清点)*

> 这些点 AI 起草时凭推测填了默认值；正式跑 `/speckit.clarify` 时应提交给用户决定。

- **CL-001**：FR-003 密码"可选"——未设密码用户后续如何登录？仅手机号 + 短信码？还是也允许后续补设密码（"设置密码" use case）？
- **CL-002**：FR-005 唯一性约束 (country_code, phone_number)——M2+ 国际号扩展时用什么策略？现在留空 country_code? 还是 hardcode `+86`?
- **CL-003**：FR-006 限流 RateLimitService backend——M1.1 单实例确认用 in-memory（mbw-shared 默认）；M2 双节点切 Redis；本 spec 不动 backend，但 SLA 受影响（in-memory 实例重启限流计数清零，是否容忍？）
- **CL-004**：FR-008 access/refresh token——secret 怎么存？环境变量？K8s secret？M1.1 docker compose secret 文件？
- **CL-005**：FR-007 隐私：是否需要 dummy bcrypt 来对齐"已注册"vs"未注册"的时延（防侧信道）？开发成本 vs 防御价值，需要明确

## Assumptions

- **A-001**：阿里云短信 API SDK 已选定（详见 `docs/architecture/tech-stack.md`），spec 不重复选型
- **A-002**：Redis 为单实例 M1.1 部署（详见 ADR-0002）；TTL 数据丢失（实例重启）容忍
- **A-003**：JWT secret 由 docker compose env 注入，spec 不指定具体路径
- **A-004**：BCrypt cost = 12（与 mbw-account/CLAUDE.md § 密码 hash 约定一致）
- **A-005**：Token TTL（access 15min / refresh 30day）按 ADR-0008 / 密码学常见实践，未来如需调整走配置项

## Out of Scope

- 设置密码（仅手机号注册用户）— 单独 use case `set-password`
- 短信码登录（仅手机号注册用户）— 单独 use case `login-by-phone-sms`
- Google / 微信 OAuth 绑定 — M1.2 / M1.3 use case
- 注销 / 删除账号 — 单独 use case `delete-account`
- 国际化短信内容 — M1.1 仅中文
- 防机器人验证码（Cloudflare Turnstile）— M1.3 引入
