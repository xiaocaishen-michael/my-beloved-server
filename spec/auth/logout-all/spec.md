# Feature Specification: Logout All Sessions (账号中心 P1.4 use case)

**Feature Branch**: `spec/auth-logout-all`
**Created**: 2026-05-02
**Status**: Draft（pending plan + tasks 联动 + analysis 通过）
**Module**: `mbw-account`（spec 组织在 `spec/auth/` 因 token lifecycle 跨登录方式横切）
**Input**: User description: "退出所有设备 endpoint — 鉴权 + 批量 revoke 该账号所有 active refresh token"

> 本 use case 是 M1.2 Phase 1 的**收尾**：完整闭环账号中心 token lifecycle（register / login → refresh → logout-all）。依赖 1.3 RefreshTokenRecord 持久化层 + `revokeAllForAccount` repo 方法，工作量明显小于 1.3。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 主流程：用户主动退出所有设备（Priority: P1）

用户在某设备上点"退出所有设备"按钮，系统 revoke 该账号下**所有** active refresh token（包含当前设备）；用户在其他设备的 access token 在剩余 TTL（最多 15min）内仍可用，但 access token 过期后无法刷新 → 必须重登。

**Why this priority**: 安全控制基线 —— 用户察觉账号异常 / 设备遗失 / 密码外泄时的标准应急动作。M1.2 账号中心闭环的最后一块拼图。

**Independent Test**: 集成测试预先注册账号 + 模拟两个设备登录拿到 `r1` / `r2` → 设备 A 调 logout-all → 设备 A 的 `r1` 调 refresh-token 返 401 + 设备 B 的 `r2` 同样返 401。

**Acceptance Scenarios**:

1. **Given** 用户登录后持有有效 access token + refresh token `r1`，**When** POST `/api/v1/auth/logout-all` Authorization: Bearer `<accessToken>`，**Then** 返回 204 + DB 内该账号所有 refresh_token 行 `revoked_at = now()`
2. **Given** 用户在两个设备分别登录持有 `r1` / `r2`，**When** 设备 A 调 logout-all，**Then** `r1` 和 `r2` 都 revoked；任一设备调 refresh-token 都返 `INVALID_CREDENTIALS`
3. **Given** logout-all 调用成功，**When** 用户在 access token 剩余 TTL 内（≤ 15min）继续访问业务 endpoint，**Then** 业务请求仍 200（access token 是无状态 JWT，**不立即** kick；详见 spec.md CL-001 设计 trade-off）
4. **Given** logout-all 调用成功 + access token 过期，**When** 客户端 401 拦截器调 refresh-token，**Then** 返 401 → 客户端清空 store + 跳转登录页

---

### User Story 2 - 鉴权失败（Priority: P1，并列）

未带 access token / token 无效 / token 过期 → 返回 401 `INVALID_CREDENTIALS`。

**Why this priority**: 安全基线 —— 无 access token 不能 revoke 任何账号的 refresh token（防伪造攻击）。

**Independent Test**: 4 场景（无 Authorization header / 伪造签名 / 过期 access token / 错误格式）都断言返 401。

**Acceptance Scenarios**:

1. **Given** 请求无 Authorization header，**When** POST `/logout-all`，**Then** 返回 `INVALID_CREDENTIALS`（HTTP 401）
2. **Given** access token 签名错（伪造）或 secret 不匹配，**When** POST `/logout-all`，**Then** 返回 `INVALID_CREDENTIALS`
3. **Given** access token TTL 过期（15min 前签的），**When** POST `/logout-all`，**Then** 返回 `INVALID_CREDENTIALS`（不暴露"过期"vs"伪造"信号）
4. **Given** Authorization 格式错（非 `Bearer <token>` 形态），**When** POST `/logout-all`，**Then** 返回 `INVALID_CREDENTIALS`

---

### User Story 3 - 边缘：限流防滥用（Priority: P2）

恶意 / bug 客户端高频调 logout-all 不会造成 DB 连环 UPDATE 风暴。

**Why this priority**: 防 DoS + 防 UPDATE 风暴（恶意大量调 logout-all 即使每次仅 revoke 0 行也会消耗 DB 连接）。

**Independent Test**: 60 秒内同 accountId 5 次请求 → 第 6 次返 429。

**Acceptance Scenarios**:

1. **Given** 同 accountId 60 秒内已请求 5 次 /logout-all，**When** 第 6 次请求，**Then** 返回 429 + `Retry-After`
2. **Given** 同 IP 60 秒内已请求 50 次 /logout-all（涵盖多账号），**When** 第 51 次请求，**Then** 返回 429（IP 维度兜底）

---

### Edge Cases

- **账号已无 active refresh token**：用户从未登录或所有 token 都已 revoke → `revokeAllForAccount` UPDATE 影响 0 行 → 仍返 204（幂等行为，不暴露"该账号无 token"信号）
- **账号 FROZEN / ANONYMIZED 状态**：access token 仍持有但账号被冻结 → 仍允许 logout-all（revoke 是清理动作，对所有 status 都应执行 — 用户主动 / 系统应急都需要）
- **并发 logout-all 同账号**：两次请求同时到 → DB UPDATE WHERE `revoked_at IS NULL` 行级锁保证幂等；两次请求都返 204
- **access token 解出的 accountId 在 DB 中不存在**：账号被硬删除（M3+ 才会出现）→ access token 仍 valid → revokeAllForAccount 影响 0 行 → 返 204（同幂等场景）
- **DB 写入失败**：`revokeAllForAccount` UPDATE 异常 → 事务回滚 → 用户收 500（极少触发）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**：endpoint = `POST /api/v1/auth/logout-all`，需 `Authorization: Bearer <accessToken>` header
- **FR-002**：成功响应 = HTTP 204 No Content（无 response body — 退出动作无返回数据语义）
- **FR-003**：access token 验签 + 解 `sub` claim → 得 `accountId`，由 Spring Security 拦截器统一处理（与既有受保护 endpoint 一致）
- **FR-004**：use case 流程：
  1. 限流（FR-006）
  2. 调 `RefreshTokenRepository.revokeAllForAccount(accountId, now)` — UPDATE refresh_token SET revoked_at = now() WHERE account_id = ? AND revoked_at IS NULL
  3. 返回 204（不论影响行数）
- **FR-005**：**幂等性**：影响 0 行（无 active token）/ 1 行（单设备）/ N 行（多设备）都返 204；客户端可重试
- **FR-006**：限流规则：
  - `logout-all:<account_id>` 60 秒 5 次（账号维度，主防）
  - `logout-all:<ip>` 60 秒 50 次（IP 维度兜底，防多账号扫描）
- **FR-007**：错误响应 `INVALID_CREDENTIALS` 用于所有鉴权失效场景（无 token / 签名错 / 过期 / 格式错），HTTP 401，**与 register / 1.1 / 1.2 / 1.3 形态字节级一致**
- **FR-008**：所有错误响应必须遵循 RFC 9457 ProblemDetail 格式（与既有一致）
- **FR-009**：use case 的事务边界：`@Transactional(rollbackFor = Throwable.class)`；revokeAllForAccount 失败 → 回滚 + 500（实际仅 1 个 DB 操作，回滚等价于 noop）
- **FR-010**：access token 在剩余 TTL 内**继续有效**（CL-001 设计 trade-off）—— 客户端业务请求在 ≤ 15min 内不受影响；refresh token 已全 revoke 故下次 refresh 必失败 → 触发重登

### Key Entities

- **RefreshTokenRecord**：复用 1.3 引入的聚合根；本 use case 仅调 `revokeAllForAccount`，无 schema 变更
- **Account（聚合根）**：复用既有；status 不影响 logout-all 行为（FROZEN 仍允许）

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**：P1 主流程端到端 P95 ≤ **150ms**（无 BCrypt + 无 SMS gateway，仅 1 次 DB UPDATE + JWT 验签）
- **SC-002**：50 个不同账号并发调 logout-all（Testcontainers 集成测试），**0 错误**；每个账号下所有 active refresh token 全 revoked
- **SC-003**：同账号 10 并发 logout-all，**全部返 204**（幂等性）+ DB UPDATE 操作收敛（行级锁 + WHERE revoked_at IS NULL 防重复 UPDATE 风暴）
- **SC-004**：限流准确性：FR-006 两条规则在集成测试中验证生效
- **SC-005**：跨设备验证：注册 1 账号 + 3 设备登录 → logout-all → 3 个 refresh token 调 refresh-token 全 401

## Clarifications

> 2 点澄清于 2026-05-02 完成。

### CL-001：access token 是否立即 kick？

**Q**：logout-all 调用后，**当前 access token** 是否立即失效？

**A**：**不立即**。理由：

- access token 是**无状态 JWT**（per 1.1 / 1.2 / 1.3 设计），server 端无 session 表 → 无法单独 revoke 一个 access token
- 立即 kick 需引入 access token redis 黑名单（每次受保护 endpoint 校验黑名单 → 增加每请求 ~1ms Redis 调用 + 黑名单大小随用户量线性增长）
- M1.2 阶段无真实用户 + 风险窗口 ≤ 15min 可接受 → 不引入黑名单
- **客户端兜底**：logout-all 成功后客户端 store 清空 → 业务页面跳转登录 → 即使 access token 还能用，UI 不再发请求

**M3+ 升级路径**：

- 引入 access token redis 黑名单：`SET access_blacklist:<jti> 1 EX <ttl_remaining>`，受保护 endpoint 校验
- 黑名单 key TTL = access token 剩余 TTL，自动过期清理

**落点**：spec.md FR-010 接受 ≤ 15min 残留；client 文档（M1.2 Phase 4）说明清 store 兜底

---

### CL-002：是否提供 logout（仅当前设备） endpoint？

**Q**：是否同时提供 `/logout`（仅 revoke 当前设备 refresh token）？

**A**：**M1.2 不提供**。理由：

- M1.2 阶段客户端"退出登录"按钮 = 仅清当前设备 store（不调后端）—— `@nvy/auth.clearSession()` 一行代码即可
- 当前设备 refresh token 在客户端被清后，攻击者拿到 token 的窗口 = 客户端清前的瞬间 + token 在网络上的传输（短于 logout-all 节省的 ~50ms）
- 引入 /logout endpoint = 加一个 UseCase + 测试 + endpoint，收益小于成本
- 对应业务场景（单设备应急退出）= 用 logout-all（多 revoke 几个 token 无副作用）

**M3+ 评估**：如客户端要求"退出本机但保留其他设备"语义（如分享设备给家人后退出自己 session）→ 引入 `/logout` revoke 当前 token

**落点**：spec.md Out of Scope 已声明；本 use case 范围明确仅 logout-all

## Assumptions

- **A-001**：复用既有 register / 1.1 / 1.2 / 1.3 Assumptions
- **A-013**：access token 的 `sub` claim = `accountId` (long)；Spring Security `JwtAuthenticationFilter` 解出后注入 `Authentication.principal`（既有，不变更）
- **A-014**：1.3 已落地 `RefreshTokenRecord` schema + `revokeAllForAccount` repo 方法 → 本 use case 仅调用，不实现 repo
- **A-015**：M1.2 阶段客户端"退出登录"按钮 = 仅清 store；"退出所有设备"按钮 = 调 logout-all + 清 store

## Out of Scope

- **`/logout` endpoint**（仅当前设备）—— per CL-002，M3+ 评估
- **access token 立即 kick**（黑名单机制）—— per CL-001，M3+ 升级
- **logout-all 通知其他设备**（push notification "你已被远程退出"）—— M3+ 引入 push 通道后
- **logout-all 审计日志 / SIEM 告警** —— M3+ 引入 SIEM 后
- **device fingerprint / session 列表展示** —— M3+ session 管理 use case
- **基于地理位置 / 设备类型的可选 revoke**（如"仅退出移动设备保留 web"）—— M3+
