# Feature Specification: Refresh Token (账号中心 P1.3 use case)

**Feature Branch**: `spec/auth-refresh-token`
**Created**: 2026-05-02
**Status**: Draft（pending plan + tasks 联动 + analysis 通过）
**Module**: `mbw-account`（spec 组织在 `spec/auth/` 目录是因为 token lifecycle 是跨登录方式的横切关注；M2+ 评估是否抽 `mbw-auth` 模块）
**Input**: User description: "refresh token 刷新 endpoint + RefreshTokenRecord 持久化 + retrofit register/login UseCases"

> 决策约束：本 use case 是 M1.2 Phase 1 中**最复杂**的一个 — 引入新表 + retrofit 三个既有 UseCase。其他 1.1 / 1.2 / 1.4 的 refresh token 持久化都靠本 use case 统一闭环。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 主流程：access token 过期，refresh 刷新（Priority: P1）

客户端在 access token 过期后（15min TTL），用 refresh token（30day TTL）调 `/refresh-token` endpoint，得到新的 access token + 新的 refresh token（rotation 防重放），原 refresh token revoke。

**Why this priority**: M1.2 账号中心闭环的核心基础设施 —— 没有 refresh-token 用户每 15 分钟需重新登录，体验不可接受。

**Independent Test**: 集成测试预先注册账号 + 登录拿 token → access token 改"expired" → 发起业务请求被 401 → 调 refresh-token 拿新 token → 重发业务请求 → 200。

**Acceptance Scenarios**:

1. **Given** 用户登录后持有有效 refresh token `r1`，**When** POST `/api/v1/auth/refresh-token` `{refreshToken: r1}`，**Then** 返回 200 + `{accountId, accessToken: a2, refreshToken: r2}`，DB 内 `r1` 标记 revoked，`r2` 持久化为新行
2. **Given** 用户已得 `r2`，**When** 再用 `r1` 调 refresh-token（重放），**Then** 返回 `INVALID_CREDENTIALS`（HTTP 401）— `r1` 已 revoked，rotation 防重放生效
3. **Given** access token 过期但 refresh 有效，**When** 客户端 `@nvy/api-client` 401 拦截器自动调 refresh-token + 重试原请求，**Then** 用户无感知（业务请求最终 200）

---

### User Story 2 - 异常：refresh token 失效（Priority: P1，并列）

refresh token 过期 / 已 revoke / 签名错 / 不存在记录 → 返回 `INVALID_CREDENTIALS`（统一形态，防泄漏失效原因）。

**Why this priority**: 安全基线 + 防枚举（不暴露"过期"vs"已 revoke"vs"伪造"信号）。

**Independent Test**: 4 个场景（过期 / revoked / 伪造签名 / 不存在）都断言返回字节级一致响应。

**Acceptance Scenarios**:

1. **Given** refresh token TTL 30day 已过（用 issuer 故意签 30day 前的），**When** POST `/refresh-token`，**Then** 返回 `INVALID_CREDENTIALS`
2. **Given** refresh token 已被 logout-all（1.4）revoke，**When** POST `/refresh-token`，**Then** 返回 `INVALID_CREDENTIALS`
3. **Given** refresh token 签名错（伪造或 secret 变更），**When** POST `/refresh-token`，**Then** 返回 `INVALID_CREDENTIALS`
4. **Given** refresh token DB 内不存在记录（攻击者构造）+ 签名错，**When** POST `/refresh-token`，**Then** 返回 `INVALID_CREDENTIALS`（与场景 3 字节级一致）

---

### User Story 3 - 边缘：限流防爆刷（Priority: P2）

恶意客户端高频调 `/refresh-token` 试图绕过 access token TTL —— 系统按 IP + token hash 限流。

**Why this priority**: 防 DoS + 防 token enumeration（攻击者拿到一个 valid refresh token 后短时大量 refresh 试图扩散影响）。

**Independent Test**: 60 秒内同 IP 100 次请求 → 第 101 次返回 429。

**Acceptance Scenarios**:

1. **Given** 同 IP 60 秒内已请求 100 次 /refresh-token（不论 token 是否有效），**When** 第 101 次请求，**Then** 返回 429 + `Retry-After`
2. **Given** 同一 refresh token hash 60 秒内已 5 次请求（即使每次返回成功 rotation），**When** 第 6 次，**Then** 返回 429 — 防客户端实现 bug 死循环爆刷

---

### Edge Cases

- **refresh token 缺失**：request body 缺 `refreshToken` → 返回 `INVALID_REQUEST`（HTTP 400，Spring Validation）
- **refresh token 格式异常**：非 256-bit base64url string → 返回 `INVALID_CREDENTIALS`（不暴露 "格式错"）
- **并发 refresh 同 token**：用户两个设备同时拿同 token 调 refresh-token → DB 唯一约束 + `revoked_at IS NULL` partial index 保证仅 1 个 rotate 成功，另一个返 `INVALID_CREDENTIALS`
- **DB 写入失败**：`RefreshTokenRecord.save` 异常 → token 已签但未持久化 → 事务回滚（per FR-008）→ 用户收 500（极少触发）
- **rotate 后 access token 还在原 TTL**：客户端可能仍持旧 access token 至 15min 过期；refresh 后旧 access 仍有效（无独立 access revoke 机制，详 1.4 logout-all 的设计 trade-off）
- **Account FROZEN / ANONYMIZED 状态**：refresh token 关联账号已 FROZEN → 返回 `INVALID_CREDENTIALS`（防枚举）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**：refresh token 入参格式 = 256-bit 随机字符串（base64url 编码 ~43 chars），与 register / 1.1 / 1.2 签发的 refresh token 格式一致
- **FR-002**：endpoint = `POST /api/v1/auth/refresh-token`，request body `{refreshToken: string}`，response 同 login `{accountId, accessToken, refreshToken}`
- **FR-003**：refresh token 持久化 schema —— `account.refresh_token` 表（**新建**，per V4 migration）：
  - `id BIGINT IDENTITY` PK
  - `token_hash VARCHAR(64) NOT NULL UNIQUE` — SHA-256 hash of refresh token raw value（**不存明文**，被 leak 时不能反推 token）
  - `account_id BIGINT NOT NULL` — 关联 Account（不加 FK，per CLAUDE.md "禁止跨 schema FK"虽然同 schema 也保守不加，便于将来拆服务）
  - `expires_at TIMESTAMP WITH TIME ZONE NOT NULL`
  - `revoked_at TIMESTAMP WITH TIME ZONE NULL` — null = 仍有效；非 null = 已 revoke
  - `created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()`
  - **索引**：
    - `uk_refresh_token_token_hash` (UNIQUE on token_hash)
    - `idx_refresh_token_account_id_active` (PARTIAL INDEX `(account_id) WHERE revoked_at IS NULL`) — 加速 1.4 logout-all 的"找 account 下所有未 revoke token"查询
- **FR-004**：refresh-token use case 流程：
  1. 限流 (FR-005)
  2. 计算 SHA-256 hash of input refresh token
  3. 查 RefreshTokenRecord by token_hash
  4. 校验记录存在 + `expires_at > now()` + `revoked_at IS NULL`
  5. 校验关联 Account 存在 + ACTIVE
  6. 签新 access token + 新 refresh token（rotate）
  7. 写新 RefreshTokenRecord
  8. revoke 旧 RefreshTokenRecord (set `revoked_at = now()`)
  9. 返回 200 + 新 token pair
- **FR-005**：限流规则：
  - `refresh:<ip>` 60 秒 100 次（IP 维度）
  - `refresh:<token_hash>` 60 秒 5 次（防客户端 bug 死循环爆刷同 token）
- **FR-006**：错误响应 `INVALID_CREDENTIALS` 用于所有失效场景（过期 / revoked / 签名错 / 记录不存在 / Account FROZEN），HTTP 401，**与 1.1 / 1.2 / register FR-006 形态字节级一致**
- **FR-007**：所有错误响应必须遵循 RFC 9457 ProblemDetail 格式（与既有一致）
- **FR-008**：refresh-token use case 的**原子性约束** —— 新 token 签 + 新记录写 + 旧记录 revoke 必须在**单一事务边界**内：
  - `@Transactional(rollbackFor = Throwable.class)`
  - **执行顺序**：限流 → 查记录 → 校验有效 → 签新 token → 写新记录 → revoke 旧记录 → commit
  - 任一失败回滚（关键场景：写新记录与 revoke 旧记录之间故障，导致两个 token 同时有效 → 必须事务保证）
- **FR-009**：retrofit register-by-phone / login-by-phone-sms / login-by-password UseCase（**本 use case 范围内**）：
  - 在 `tokenIssuer.signRefresh()` 后立即写 RefreshTokenRecord（hash + accountId + expiresAt = now() + 30day + revoked_at = NULL）
  - 写入失败回滚整体事务（与 register FR-011 / 1.1 FR-010 / 1.2 FR-010 原有事务边界对齐）
  - **影响 PR**：本 use case 实施 PR (PR 2) 内同时改 3 个既有 UseCase + 加测试覆盖回填

### Key Entities

- **RefreshTokenRecord（新增聚合根）**：refresh token 的 server-side persistence。属性：`id` / `tokenHash` (SHA-256) / `accountId` / `expiresAt` / `revokedAt` (nullable) / `createdAt`
- **Account（聚合根）**：复用既有；不变更

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**：P1 主流程端到端 P95 ≤ **300ms**（无 BCrypt 计算 + 无 SMS gateway，仅 PG 查 + 写 + JWT 签）
- **SC-002**：100 个不同 refresh token 并发 rotate（Testcontainers 集成测试），**0 错误，rotation 数与请求数完全一致**
- **SC-003**：同一 refresh token 10 并发 rotate，**仅 1 个成功**（DB 唯一约束 + revoked_at partial index 保证）
- **SC-004**：retrofit 验证：register / login-sms / login-password 三个 UseCase 完成签 token 后，DB `account.refresh_token` 表必有对应行（hash + accountId + 未 revoke）
- **SC-005**：限流准确性：FR-005 两条规则在集成测试中验证生效

## Clarifications

> 3 点澄清于 2026-05-02 完成。

### CL-001：refresh token 存原文 vs hash

**Q**：`refresh_token.token_hash` 列存 raw token 还是 SHA-256 hash？

**A**：**SHA-256 hash**。理由：

- DB 被 leak 时不能直接反推 token（hash 不可逆）
- 客户端持有的 raw token + DB 内 hash 双向匹配：write 时 `tokenHash = SHA-256(rawToken)`；verify 时同样 hash 后查表
- SHA-256（vs BCrypt）够用 —— refresh token 本身是 256-bit 高熵随机串，无需 cost 防爆破；查询时不需要 cost=12 的 ~100ms 延迟（vs password BCrypt 需要 cost）
- **不**用 keyed HMAC：M1.2 不引入 server-side secret 旋转复杂度，pure SHA-256 已够强

**落点**：FR-003 token_hash 列加 SHA-256 注释；implement 阶段 `RefreshTokenHasher` domain service 封装 hash 算法（便于 M2+ 切 keyed HMAC）

### CL-002：refresh token rotation 还是 long-lived

**Q**：refresh-token 用 rotation（每次返新 refresh token + revoke 旧）还是 long-lived（refresh token 不变，多次使用）？

**A**：**rotation**（rotation on each use）。理由：

- **防重放**：旧 refresh token 被 leak 后立即用 = 新的旧 token 同时被 server 看到→可触发重放检测告警（M3+ 引入）
- **行业最佳实践**：OWASP / RFC 6819 推荐 rotation
- **客户端复杂度可接受**：`@nvy/api-client` 401 拦截器接 refresh → store 新 refresh token，已经是标准 pattern
- **代价**：DB 多写（每 refresh 多写 1 行 + 1 行 revoke），M1.2 量级可忽略

**落点**：FR-004 流程显式 rotation；客户端 `@nvy/api-client` 内 401 拦截器需更新 store 内 refresh token

### CL-003：1.3 之前签的 refresh token 怎么办？

**Q**：1.1 / 1.2 完成后到 1.3 实施之前用户登录拿到的 refresh token，1.3 实施时怎么处理？

**A**：**接受窗口期 invalidation**。理由：

- M1.2 阶段无真实用户（CLAUDE.md "expand-migrate-contract 跳步条件 1"）
- 1.3 实施 PR 落地时所有此前签的 refresh token **未持久化** → 调 refresh-token 都返 `INVALID_CREDENTIALS`（DB 内查不到对应记录）
- 用户重新走 login 即可拿到新 token（含 RefreshTokenRecord 持久化）
- 后端不需主动通知 / 兼容 / 迁移
- 测试期间相当于"系统 reset"窗口

**落点**：FR-009 retrofit 段加段说明；M1.2 plan 接受此窗口期

## Assumptions

- **A-001**：复用既有 register / 1.1 / 1.2 Assumptions
- **A-010**：refresh token 的 raw value 由 `TokenIssuer.signRefresh()` 生成（256-bit secure random，base64url 编码）；本 use case 不变更生成逻辑，仅加持久化层
- **A-011**：SHA-256 hash 选择：标准 java.security.MessageDigest("SHA-256")，无 keyed HMAC（M2+ 评估升 keyed HMAC）
- **A-012**：1.3 实施 PR 落地时间窗（与 1.4 logout-all 实施时间窗）应紧跟 1.1 / 1.2 实施 PR；中间窗口期登录的用户接受重登

## Out of Scope

- **logout-all use case**（批量 revoke 所有 refresh token） — Phase 1.4 引入，依赖本 use case 的 RefreshTokenRecord schema
- **session 多设备管理 / device fingerprint** — M3+ 评估
- **refresh token 反向加密**（keyed HMAC） — M2+ 升级
- **refresh token 重放检测告警**（旧 token 被使用 → 触发 logout-all） — M3+ 引入 SIEM 后
- **基于 IP / device fingerprint 的 refresh 风控** — M3+ 引入
- **Refresh token TTL 配置化**（默认 30day） — M2+ 评估，目前 hardcoded
