# Feature Specification: Device Management（登录管理 — 列设备 + 单设备移除）

**Feature Branch**: `feature/device-management`
**Created**: 2026-05-08
**Status**: Draft（pending plan + tasks，等 app spec + mockup 联动后回填）
**Module**: `mbw-account`（spec 组织在 `specs/account/` 与 `delete-account` / `cancel-deletion` 同级；token 维度沿用既有 `account.refresh_token` 表，不另开 schema）
**Input**: User description: "用户在『设置 → 账号与安全 → 登录管理』里看到自己当前账号的全部已登录设备（已登录的设备 N + 列表 + 设备图标 + 设备名 + 本机标记 + 最近活跃时间 + 登录地点），点单条进详情（设备名 / 登录地点 / 登录方式 / 最近活跃），可以点『移除该设备』revoke 该设备的 refresh token。本机不允许从这里移除。"

> **Context**：[PRD § 5.4 强制退出其他设备](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#54-强制退出其他设备) 描述「账号安全 - 登录设备」列表 + 单设备退出。PRD v2 把 device fingerprint / per-device session 列表标注「M3+ 引入」（参 [`../../auth/refresh-token/spec.md`](../../auth/refresh-token/spec.md) Out of Scope 末尾），本 spec **将其前置到 M1.X**：产品需要、实现成本可控、与 mbw-account refresh_token 表一次扩展即可，无需引 LoginAudit / device fingerprint 第三方组件。
>
> **架构决策**（plan.md 落地）：
>
> - **数据源 = `account.refresh_token` 既有表 + 5 列扩展**（device_id / device_name / device_type / ip_address / login_method），不引入 LoginAudit；rotation-on-each-use 语义下，"一台活跃设备" = "该设备最新一条 `revoked_at IS NULL` 的 refresh_token row"
> - **本机识别 = JWT access token `did` claim**：login / refresh 路径在签 access token 时把 device_id 写入 custom claim；GET /devices 解 access token 即可标记 isCurrent；DELETE 路径在 server 端比对 did 拒绝 revoke 当前 device（per Q4 决议）
> - **GeoIP = ip2region 嵌入式离线库**（per Q1 决议，`net.dreamlu:mica-ip2region` 或上游 `net.dreamlu`/`lionsoul:ip2region` Java port）：MIT-like license / 离线 / 国内省市精度 / 内存 ~5MB、查询 < 1 µs / 无第三方网络依赖
> - **device_id 来源 = client 持久化 UUID v4**（per Q2 决议）：client 首次启动生成 UUID 写 secure store，登录 / refresh 时 `X-Device-Id` header 上报；服务端不解析 User-Agent 的 fingerprint（M3+ 再说）
> - **device_name 来源**：native 用 `expo-device.deviceName`（"MK-iPhone"）+ `expo-device.modelName` 兜底；web 服务端从 User-Agent 粗解析（"macOS" / "Windows" / "iOS Safari" 这类，不细分型号）；M2+ 加用户自改入口（per Q2 决议第 c 档）
> - **login_method = enum**：当前仅 `PHONE_SMS`；预留 `GOOGLE` / `APPLE` / `WECHAT` / `REFRESH`（refresh 路径生成的新 row 沿用旧 row 的 login_method）

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 主流程：用户列出当前账号已登录设备（Priority: P1）

已登录用户在 app 设置中点「登录管理」→ 调 GET /devices → 返回当前账号所有 active refresh_token 折算的设备列表，含本机标记 + 设备名 / 类型 / 最近活跃 / 登录地点。

**Why this priority**: 主路径，所有列表页必经；无此 endpoint 整个 feat 失效。

**Independent Test**: Testcontainers PG + Redis；预设账号 +8613800138000；模拟 3 次成功登录(同账号 3 不同 device_id)→ DB 写 3 条 active refresh_token；当前 device 持有 access token (did = device_id_2)；GET /api/v1/auth/devices?page=0&size=10 → 返回 3 条 + 仅 device_id_2 那条 `isCurrent=true`。

**Acceptance Scenarios**:

1. **Given** 账号 +8613800138000 处 ACTIVE，3 个不同 device_id 各持一条 active refresh_token，**When** 该账号任一 access token 调 GET `/api/v1/auth/devices?page=0&size=10`，**Then** 200 + `{ items: [3 items], totalElements: 3, totalPages: 1, page: 0, size: 10 }`；items 按 `created_at DESC` 排序；每个 item 含 `{ id, deviceId, deviceName, deviceType, ipAddress, location, loginMethod, lastActiveAt, isCurrent }`；当前 access token did 对应的那条 `isCurrent=true`，其余 `false`
2. **Given** 续 1，**When** 解析 items[*].location，**Then** 命中 ip2region 的 IP 返回中文省市（"上海" / "北京" / "广东深圳"）；私网 / loopback / 解析失败 → location = `null`，client 渲染 "—"
3. **Given** 账号有 1 条 active refresh_token（仅本机），**When** 调 GET /devices，**Then** 200 + `{ items: [1 item], totalElements: 1 }` + 该条 `isCurrent=true`
4. **Given** 同账号有 12 条 active refresh_token + size=10，**When** 调 GET /devices?page=0&size=10，**Then** 200 + `items.length=10, totalElements=12, totalPages=2`；page=1 时返回剩 2 条
5. **Given** 续 4，**When** 用未在 list 第 0 页的 access token did(在第 1 页)调 GET /devices?page=0，**Then** 第 0 页 items 中**无** `isCurrent=true`，第 1 页该条 `isCurrent=true`（client 翻页才看到本机标记 — 设计代价，per Q3 分页决策）

---

### User Story 2 - 用户移除非本机设备（Priority: P1，并列）

用户在 detail 页点「移除该设备」→ confirm sheet → 「移除」→ 调 DELETE /devices/{recordId} → server revoke 该 row → 200 + 该 device 在剩余 access token TTL（≤ 15 min）后无法刷新强制下线。

**Why this priority**: 主路径，feat 核心交付物。

**Independent Test**: 续 User Story 1 的 fixture（3 active rows）；当前设备 device_id_2 调 DELETE /devices/{recordId_1} → 200；DB row_1.revoked_at != null；后续 GET /devices 返回 2 条；持 row_1 关联 refresh_token 调 /api/v1/auth/refresh-token → 401。

**Acceptance Scenarios**:

1. **Given** 账号有 3 active refresh_token，当前 access token did = device_id_2，**When** DELETE `/api/v1/auth/devices/{recordId_1}`，**Then** 200 No Content；DB `refresh_token` row id=recordId_1 `revoked_at = now()`；其他 2 条不变
2. **Given** 续 1，**When** 持 device_1 旧 refresh_token 调 POST /api/v1/auth/refresh-token，**Then** 401 INVALID_CREDENTIALS（refresh-token spec FR-007 路径）
3. **Given** 续 1，**When** GET /devices 再查，**Then** 返回 2 条（被移除的不显示）
4. **Given** 续 1，**When** 持 device_1 旧 access token（剩余 TTL ≤ 15 min）调业务 endpoint，**Then** 仍 200（access token 是无状态 JWT，不立即 kick — 与 logout-all spec CL-001 同 trade-off）
5. **Given** 账号 + recordId 不属于该账号（属其他用户），**When** DELETE /devices/{recordId}，**Then** 404 DEVICE_NOT_FOUND（不暴露"是否存在但不属本人"，反枚举）

---

### User Story 3 - 拒绝移除当前 device（Priority: P1，并列）

server 强制校验 did 匹配，拒绝 client 通过 DELETE 自删当前 refresh_token row（即使 client 篡改 UI 隐藏的"移除"按钮）。

**Why this priority**: per Q4 决议，server 不能信任 client 的 UI 约束；本机退出登录走「设置 → 退出登录」（spec B account-settings-shell 已落地的 logoutAll），不走本 endpoint。

**Independent Test**: 当前 access token did = device_id_2 + 该账号 active refresh_token 含 recordId_2 (device_id = device_id_2)；DELETE /devices/{recordId_2} → 409 CANNOT_REMOVE_CURRENT_DEVICE。

**Acceptance Scenarios**:

1. **Given** 当前 access token did = device_id_2，账号 active refresh_token 包含 recordId_2 (device_id = device_id_2)，**When** DELETE `/api/v1/auth/devices/{recordId_2}`，**Then** 409 CANNOT_REMOVE_CURRENT_DEVICE + 文案"当前设备请通过『退出登录』移除"；DB 不变
2. **Given** access token 缺 `did` claim（旧版 token，过渡期）, **When** DELETE /devices/{anyRecordId}，**Then** 401 INVALID_CREDENTIALS（要求 client 重 login 拿新 token，避免老 token bypass 校验）
3. **Given** 账号 access token did = device_id_2，但 device_id_2 在 DB 中无 active row（极端：rotation 进行中 / DB 不一致），**When** DELETE /devices/{recordId_other}，**Then** 仍按"非当前"路径 200 移除（did 仅做 reject 校验，缺 row 不卡正常 revoke 路径）

---

### User Story 4 - 鉴权失败（Priority: P1，并列）

未带 access token / token 无效 / token 过期 → GET / DELETE 均返 401 INVALID_CREDENTIALS。与 logout-all spec User Story 2 同模式。

**Why this priority**: 安全基线 —— 未鉴权不能列举 / 移除任何账号的设备。

**Acceptance Scenarios**:

1. **Given** 请求无 Authorization header，**When** GET /devices 或 DELETE /devices/{id}，**Then** 401 INVALID_CREDENTIALS
2. **Given** access token 签名错 / 过期 / 格式错，**When** 同上，**Then** 401（不暴露"过期"vs"伪造"）
3. **Given** access token 关联账号已 FROZEN / ANONYMIZED，**When** GET /devices，**Then** 403 ACCOUNT_IN_FREEZE_PERIOD / 401（沿用既有 expose-frozen-account-status spec D 的拦截链路 — 已 ship at #143）

---

### User Story 5 - 限流防滥用（Priority: P2）

恶意 / bug 客户端高频 GET 列表 / DELETE 不会让 DB 风暴。

**Why this priority**: 防 DoS（GET 一次返 N 条 + ip2region 解析有计算开销）+ 防 UPDATE 风暴。

**Acceptance Scenarios**:

1. **Given** 同 accountId 60 秒内 GET /devices 已 30 次，**When** 第 31 次，**Then** 429 + Retry-After
2. **Given** 同 accountId 60 秒内 DELETE 已 5 次，**When** 第 6 次，**Then** 429 + Retry-After
3. **Given** 同 IP 60 秒内 GET 已 100 次（涵盖多账号），**When** 第 101 次，**Then** 429（IP 维度兜底）

---

### Edge Cases

- **设备首次登录上报缺 X-Device-Id header**：server 端 fallback 用 `random UUID v4`，row 仍写入；UI 列表显示该 row 但 `deviceId` 不稳定 → 后续真带 X-Device-Id 的 row 视为不同 device。**接受这种小 noise**（client 应按 spec 实现持久化 UUID；缺失场景仅在 hack / 旧 client 出现）
- **设备 IP 变化（手机切 4G ↔ WiFi）**：当前 row 的 ip_address 是该 row insert 时的 IP，rotation 后新 row 才更新 IP；list 显示"最近一次刷新时的 IP"，不实时
- **device_name 含 emoji / 非 ASCII**：DB 列宽 `VARCHAR(64)`（兼容 "MK-iPhone 📱" + 中文 "张磊的 Mate 50"），server 端 trim + max-length 校验；超长 `VALIDATION_FAILED`
- **同 device_id 多 active row（不该出现）**：rotation 时本应 revoke 旧 row 后插新 row，原子性保证 1 device 1 active row。若 DB 出现 2 active row(同 device_id)→ list 取最新 created_at 那条，旧的视为孤儿 row（not-emit），后台清理 job 兜底（M2+ 排期）
- **list 无任何 active row（理论不可达 — 当前 access token 必对应 1 active row）**：返 200 + items=[] + totalElements=0；client UI "已登录的设备 0"
- **GET /devices?size > 100**：clamp 到 100，避免一次拉太多 row + ip2region 解析阻塞
- **DELETE /devices/{recordId} recordId 已 revoked**：200 No Content（幂等行为；防 client 误重复点击）
- **DELETE /devices/{recordId} recordId 不存在**：404 DEVICE_NOT_FOUND（与"属于其他账号"同响应，反枚举）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001（list endpoint）**：`GET /api/v1/auth/devices?page={int>=0}&size={int 1..100, default 10}`；需 `Authorization: Bearer <accessToken>`；响应 200 + `DeviceListResponse { page: int, size: int, totalElements: long, totalPages: int, items: DeviceItem[] }`，items 按 `lastActiveAt DESC` 排序
- **FR-002（list item schema）**：`DeviceItem { id: long, deviceId: string|null, deviceName: string, deviceType: enum["PHONE","TABLET","DESKTOP","WEB","UNKNOWN"], ipAddress: string|null, location: string|null, loginMethod: enum["PHONE_SMS","GOOGLE","APPLE","WECHAT","REFRESH"], lastActiveAt: ISO-8601, isCurrent: boolean }`
- **FR-003（revoke endpoint）**：`DELETE /api/v1/auth/devices/{recordId}`；需 access token；成功 200 No Content（幂等：已 revoked 的 row 也 200）
- **FR-004（本机识别）**：access token 必含 custom claim `did: string`；GET /devices 标记 `isCurrent = (item.deviceId == accessToken.did)`
- **FR-005（拒移除当前 device）**：DELETE 路径 server-side 校验 — 若 `refresh_token[recordId].device_id == accessToken.did` → 拒 409 `CANNOT_REMOVE_CURRENT_DEVICE`，body message "当前设备请通过『退出登录』移除"
- **FR-006（缺 did claim）**：access token 不含 `did` claim → 任何 device-management endpoint 均 401（防过渡期老 token bypass FR-005 校验）
- **FR-007（schema 变更）**：`account.refresh_token` 加 5 列：
  - `device_id VARCHAR(36) NULL`：UUID v4，client 持久化上报；缺失 fallback `gen_random_uuid()`
  - `device_name VARCHAR(64) NULL`：客户端上报 + server trim；NULL → UI 显示 deviceType 兜底文案（"未知设备"）
  - `device_type VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'`：UA 解析 + client hint 推导
  - `ip_address VARCHAR(45) NULL`：IPv4 / IPv6 都兼容（IPv6 最长 45 字符）；server 端从 X-Forwarded-For / RemoteAddr 取首段
  - `login_method VARCHAR(16) NOT NULL DEFAULT 'PHONE_SMS'`：当前仅 PHONE_SMS / REFRESH，预留 GOOGLE / APPLE / WECHAT
  - 加索引 `idx_refresh_token_account_device_active ON (account_id, device_id) WHERE revoked_at IS NULL`：list / 本机识别走该索引
- **FR-008（access token claim 扩展）**：`JwtTokenIssuer.signAccess(...)` 入参增 `deviceId`；signed JWT 多一个 `did` claim；refresh-token / phone-sms-auth / cancel-deletion / register-by-phone 等所有签 access token 路径同步改入参；老 token（M1.X 升级前签的）按 FR-006 处理
- **FR-009（client 上报路径）**：所有签 token 入口（POST /accounts/phone-sms-auth / POST /auth/refresh-token / POST /auth/cancel-deletion）支持 header：
  - `X-Device-Id: <UUID v4>` — 必带；缺则 server fallback gen UUID
  - `X-Device-Name: <string ≤ 64 char>` — 可选；缺则 server 解析 UA fallback
  - `X-Device-Type: PHONE|TABLET|DESKTOP|WEB|UNKNOWN` — 可选；缺则 server UA 解析 fallback
  - 上述三 header 在 use case 入参 Command 透传到 RefreshTokenRecord
- **FR-010（IP 提取）**：`HttpServletRequest.getHeader("X-Forwarded-For")` 取**最左非内网 IP**（per nginx 配置 trust real proxy chain）；缺失 fallback `request.getRemoteAddr()`；私网 / loopback / 公司 NAT 段过滤后 → 入库 NULL
- **FR-011（GeoIP 解析）**：DTO mapping 阶段（infrastructure → web response）调 `Ip2RegionService.resolve(ipAddress)` 返中文省市；解析失败 / null IP / 私网 IP → location=null
- **FR-012（rotation 保留 device 元数据）**：`/auth/refresh-token` 路径每次 revoke 旧 row + insert 新 row 时，新 row 继承旧 row 的 `device_id` / `device_name` / `device_type` / `login_method`；`ip_address` 用本次 refresh 请求的 IP（**新值**，体现"最近一次刷新位置"）；新 row `login_method = REFRESH` ❌ — 反例，**应继承旧 row 的 login_method**（保持本设备首次登录方式不丢）；refresh 不开新 login_method 值
- **FR-013（限流）**：
  - GET /devices：account 维度 60s 30 次；IP 维度 60s 100 次
  - DELETE /devices/{id}：account 维度 60s 5 次；IP 维度 60s 20 次
  - 超限 → 429 ProblemDetail + `Retry-After`
- **FR-014（错误响应格式）**：RFC 9457 ProblemDetail；错误码：
  - `INVALID_CREDENTIALS` 401（鉴权失败 / 缺 did claim）
  - `ACCOUNT_IN_FREEZE_PERIOD` 403（账号 FROZEN，per spec D 既有拦截链）
  - `DEVICE_NOT_FOUND` 404（recordId 不存在 / 不属本账号 — 反枚举字节级一致）
  - `CANNOT_REMOVE_CURRENT_DEVICE` 409（per FR-005）
  - `RATE_LIMITED` 429
  - `VALIDATION_FAILED` 400
- **FR-015（OpenAPI 暴露）**：Springdoc 自动从 controller 推导；`DeviceItem` / `DeviceListResponse` 进 schema 供前端 generator 消费
- **FR-016（不修改既有 token lifecycle 行为）**：phone-sms-auth / register-by-phone / refresh-token / logout-all / cancel-deletion 现有响应 schema 字节级不变；本 spec 仅扩入参 header + DB 列；access token 多 `did` claim 是 OAuth/JWT 标准约定的可选 claim，不破坏现有 client 解析
- **FR-017（事件）**：device 移除成功路径 publish `DeviceRevokedEvent`：
  - Payload：`(AccountId accountId, RefreshTokenRecordId recordId, String deviceId, Instant revokedAt, Instant occurredAt)`
  - Outbox：Spring Modulith Event Publication Registry
  - 当前消费方：本期无；为 M2+ 安全审计 / 通知模块预留
- **FR-018（埋点）**：device 移除成功后埋点 `device_revoke` + `loginMethod` + `daysSinceCreated`（per PRD § 5.9 概念，本期 commented `// TODO`）

### Key Entities

- **RefreshTokenRecord（聚合根，扩展既有）**：复用 V5 既有结构 + 5 新列；domain model 加 `deviceId` / `deviceName` / `deviceType` / `ipAddress` / `loginMethod` 字段（值对象包装见下）；`createActive(...)` factory 入参增加 device 元数据；`reconstitute(...)` 同
- **DeviceId（值对象，新）**：`record DeviceId(String value)`；约束：UUID v4 格式（`Pattern.compile("^[0-9a-f]{8}-...$")`）；fromHeaderOrFallback(headerValue) 静态工厂兼容缺失场景
- **DeviceName（值对象，新）**：`record DeviceName(String value)`；max length 64；trim + 非空校验；nullable wrapper `DeviceName::ofNullable`
- **DeviceType（enum，新）**：`PHONE` / `TABLET` / `DESKTOP` / `WEB` / `UNKNOWN`；放 `mbw-account.api.dto`（跨模块可见，未来 mbw-pkm 等观察事件可用）
- **LoginMethod（enum，新）**：`PHONE_SMS` / `GOOGLE` / `APPLE` / `WECHAT` / `REFRESH` ❌ — REFRESH **不入** enum（per FR-012：refresh 路径继承旧值，不引入 REFRESH 作枚举）；最终 enum = `PHONE_SMS` / `GOOGLE` / `APPLE` / `WECHAT`
- **IpAddress（值对象，新）**：`record IpAddress(String value)`；`Pattern` 校验 IPv4 + IPv6 形式；`isPrivate()` 判内网（`10.x` / `172.16-31.x` / `192.168.x` / `127.x` / IPv6 `::1` / `fc00::/7`）→ persist 时若 private 写 NULL
- **Ip2RegionService（domain service，新）**：`resolve(IpAddress) -> Optional<String>` 返中文省市或空；wraps `net.dreamlu:mica-ip2region` 或 `lionsoul:ip2region` 的 Java port（plan.md 阶段二选一）；测试可注入 fake
- **DeviceRevokedEvent（domain event，新）**：record `(AccountId accountId, RefreshTokenRecordId recordId, DeviceId deviceId, Instant revokedAt, Instant occurredAt)`；放 `mbw-account.api.event`
- **ListDevicesUseCase（application，新）**：`(AccountId, DeviceId currentDeviceId, int page, int size) -> DeviceListResult`；调 RefreshTokenRepository.findActiveByAccountId(...) + 标 isCurrent + 经 `Ip2RegionService` 解 location
- **RevokeDeviceUseCase（application，新）**：`(AccountId, RefreshTokenRecordId, DeviceId currentDeviceId) -> void`；事务内：findById + 校验 ownership + 校验 deviceId != currentDeviceId（FR-005）+ revoke + publish event；幂等
- **RefreshTokenRepository（接口，扩展）**：新增 `findActiveByAccountId(AccountId, Pageable) -> Page<RefreshTokenRecord>`、`findById(RefreshTokenRecordId) -> Optional<RefreshTokenRecord>` 已有则复用
- **JwtTokenIssuer（domain service，扩展）**：`signAccess(AccountId, DeviceId)` 增 deviceId 入参，emit JWT 含 `did` claim；既有 `signAccess(AccountId)` 标 deprecated 后拆改

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**：list P95 ≤ 200ms（含 ip2region 解析 N 条；N≤10 默认 size）
- **SC-002**：revoke P95 ≤ 100ms（单 row UPDATE + outbox + audit）
- **SC-003**：revoke 原子性 — IT 模拟 outbox 写入抛异常 → 全部回滚（refresh_token 仍 active + 无 DeviceRevokedEvent）
- **SC-004**：本机识别正确性 — IT 验 5 个 device_id 中仅 access token did 对应那条 isCurrent=true，其余 false；recordId 跨账号不返（404）
- **SC-005**：拒删当前 device — IT 验 DELETE /devices/{recordOfCurrentDevice} → 409 + DB 不变
- **SC-006**：rotation 元数据传递 — IT 验 refresh-token rotation 后新 row.device_id / device_name / device_type / login_method 全部继承旧 row；ip_address 是新请求的 IP；旧 row revoked_at != null
- **SC-007**：反枚举 — IT 验 DELETE /devices/{recordId_of_other_account} 返 404；DELETE /devices/{nonexistent_id} 也 404；时延差 ≤ 50ms
- **SC-008**：限流准确性 — FR-013 四条规则集成测试覆盖；429 + 正确 `Retry-After`
- **SC-009**：GeoIP 准确性 — IT 注入 5 个固定 IP（北上广深杭）→ 解析中文省市命中；ip2region 库版本 pinned
- **SC-010**：缺 did claim 拦截 — IT 用旧版 access token（无 did claim）调 GET / DELETE → 401；client 必须重 login 拿新 token
- **SC-011**：ArchUnit / Spring Modulith Verifier CI 仍 0 violation
- **SC-012**：OpenAPI snapshot 含 GET /devices + DELETE /devices/{id} + DeviceItem schema
- **SC-013**：device-management 不破坏 token lifecycle — refresh-token / logout-all / cancel-deletion / phone-sms-auth 既有 IT 全部 GREEN（regression 0）

## Clarifications

> 2 题待 user 在后续 `/speckit.clarify` 阶段或 plan 起草时确认。其余决策已在本文档 Context 段或 user Q1-Q6 决议中固化。

### CL-001：device_id fallback 策略 — server 生成还是拒绝请求

**Q**：FR-009 X-Device-Id 缺失场景：(a) server 生成 UUID v4 落库（透明降级，client 老版本仍可登录但 device 无法持续追踪）/ (b) 拒绝 400 VALIDATION_FAILED（强制 client 升级）/ (c) 仅在登录路径拒，refresh 路径降级（兼容老 client 在 refresh 时丢 X-Device-Id）。

**推荐**：**(a) server 生成 UUID v4 落库**。理由：

- M1.X 阶段 client 与 server 同步升级，正常路径 client 都带 header
- (a) 兼容旧 client / 第三方调试工具（curl / Postman），降低 onboarding 摩擦
- noise 影响仅限 device-management UI 列表"看似新设备"，**不破坏 token lifecycle 安全**
- M3+ 内测前可改为 (b) 强校验

**反方观点**：(a) 让攻击者每次 hit 都创出新 device row → DB 浪费。但被限流 + access token 鉴权前置过滤，实际攻击成本高于收益。

**落点**：FR-009 显式声明 fallback；plan.md 落 `DeviceIdHeaderResolver.fromHeaderOrFallback(...)` 工具方法。

---

### CL-002：DELETE /devices 接口的 ip_address / device_name 是否对外暴露

**Q**：FR-002 list response 含 ipAddress + 解析后 location。直接 IP 字符串外暴是否过度（隐私 + 攻击者 enumerate 自家所有设备 IP 用作横向移动情报）？

**推荐**：**list response 仅暴 location（中文省市），不暴 raw ipAddress**。理由：

- raw IP 对用户识别本机 / 异常登录场景帮助有限（用户不会盯 IP 看），location（"上海"）已足够
- IP 外暴增加攻击面（拖库后 attacker 拿 IP 横向移动 / 关联账号去匿名化）
- API response schema 仍预留 `ipAddress: string|null` 字段，但**仅 admin endpoint** 暴露，本 spec 范围不暴

**反方观点**：技术用户希望看到 raw IP（pro 用户场景）— 但 PRD v2 单人产品阶段无此用户画像。

**落点**：FR-002 修订 — `DeviceItem` 删 `ipAddress` 字段，仅 `location: string|null`；schema 加注释 `// raw IP available only via admin endpoint, not in user-facing /devices`。**这点 plan 阶段需 user 二次拍板**，因影响 OpenAPI schema。

## Assumptions

- **A-001**：复用既有 `RateLimitService` / `JwtTokenIssuer`(扩展) / `RefreshTokenRepository`(扩展) / `AccountRepository`
- **A-002**：refresh-token spec FR-009 既有 rotation-on-each-use 行为不变，本 spec 仅在 rotation 路径多搬 5 列元数据
- **A-003**：Spring Security 既有鉴权链拦截 401 / 403 路径（含 spec D 已 ship 的 ACCOUNT_IN_FREEZE_PERIOD 拦截）
- **A-004**：M1.X 单 PR 落 schema migration（per [my-beloved-server CLAUDE.md § expand-migrate-contract](https://github.com/xiaocaishen-michael/my-beloved-server/blob/main/CLAUDE.md#不兼容变更expand-migrate-contract-三步法) "M3 内测前允许跳步"），不拆 V11/V12/V13 三步
- **A-005**：本 spec 不引 LoginAudit 表 / device fingerprint 第三方组件；M3+ 评估
- **A-006**：JWT signing key 与既有 access token 共享；新 `did` claim 不影响既有签发 / 验签链路
- **A-007**：app 端 SDD spec 位于 `apps/native/spec/device-management/`，与本 server spec 独立 review；data contract 锚 OpenAPI 自动生成（per meta CLAUDE.md API 契约）

## Out of Scope

- **「修改设备名称」交互**（per Q2 决议）— M2+ 单独 spec
- **设备级双因素认证 / 异常登录通知 / 邮件提醒** — 单独 spec，预留 `DeviceRevokedEvent` 给通知模块订阅
- **device fingerprint 高级解析**（screen / canvas / WebGL hash）— M3+ 评估
- **admin 后台批量 revoke** — admin 模块引入后单独 spec
- **LoginAudit 表 / 完整审计日志** — 单独 spec，PRD § 5.4 提及但本 spec 不实现
- **登录方式枚举 GOOGLE / APPLE / WECHAT 实际接入** — 各自 OAuth use case；本 spec 仅在 enum 中预留值
- **「最近活跃」精度提升到秒级**（now 是 access token TTL ≤ 15 min 精度）— 需新增 last_used_at 列 + 每个业务 endpoint 都 UPDATE，性能代价大；M3+ 评估
- **app 端 UI 实现**（list 页 / detail 页 / sheet） — `apps/native/spec/device-management/`
- **PRD § 5.4 文本修订**（drift：原描述"读 LoginAudit"，实际本 spec 改用 refresh_token 表） — 单独 docs PR

## References

- [`../delete-account/spec.md`](../delete-account/spec.md) / [`../cancel-deletion/spec.md`](../cancel-deletion/spec.md) — 同级 spec 模板（spec-kit 三段格式）
- [`../../auth/refresh-token/spec.md`](../../auth/refresh-token/spec.md) — RefreshTokenRecord schema + rotation 语义
- [`../../auth/logout-all/spec.md`](../../auth/logout-all/spec.md) — 鉴权 + 限流 pattern；本 spec User Story 4 复用
- [`../expose-frozen-account-status/spec.md`](../expose-frozen-account-status/spec.md) — 既有 ACCOUNT_IN_FREEZE_PERIOD 拦截链（spec D，已 ship #143）
- [PRD § 5.4 强制退出其他设备](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#54-强制退出其他设备)
- [meta CLAUDE.md § 模块化策略](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/architecture/modular-strategy.md)
- [my-beloved-server CLAUDE.md § expand-migrate-contract](https://github.com/xiaocaishen-michael/my-beloved-server/blob/main/CLAUDE.md#不兼容变更expand-migrate-contract-三步法)
