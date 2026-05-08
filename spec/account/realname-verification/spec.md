# Feature Specification: Realname Verification (实名认证 use case)

**Feature Branch**: `docs/realname-verification-spec`
**Created**: 2026-05-08
**Status**: Draft（pending impl）
**Module**: `mbw-account`
**Input**: User description: "实名认证 — 仅大陆二代证 + 三要素 + 实人活体；阿里云实人认证；VERIFIED 终态不可解绑（M1 锁死，M2 客服核身工单）；AES-GCM 加密入库 + KMS；mask 显示；dev-bypass env hatch。详见 PRD § 5.10。"

> **下游 / 关联**：
>
> - 前端 spec：[`apps/native/spec/realname/`](https://github.com/xiaocaishen-michael/no-vain-years-app/tree/main/apps/native/spec/realname)（待开 PR；按 ADR-0017 类 1 标准 UI 流程：业务流先行，mockup 后置）
> - 路由锚点：app 仓 `(app)/settings/account-security/realname`；当前 `account-security/index.tsx` 第 11 行 `realname:` 项目为 disabled 占位，本 use case ship 后转 enabled
> - PRD 上游：[account-center.v2 § 5.10](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#510-实名认证m1x-引入)（meta PR #65 ship）

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 主流程：未实名用户首次完成认证（Priority: P1）

未实名（status=UNVERIFIED）的 ACTIVE 用户进入「设置 → 账号与安全 → 实名认证」，输入真实姓名 + 身份证号、勾选授权协议、提交 → 服务端 init 阿里云实人认证 → 客户端跳活体 SDK（阿里云全屏接管）→ SDK 完成 → 客户端轮询查结果 → 服务端从阿里云查公安比对 + 活体结果 → 状态转 VERIFIED → 客户端跳 readonly 视图（图 3）。

**Why this priority**: 主路径，决定该 use case 是否成立；所有下游能力（M2+ 提现 / 达人入驻 / 找回账号）依赖此入口。

**Independent Test**: Testcontainers 起 PG + Redis + WireMock（mock 阿里云实人认证 API）；预设 ACTIVE 账号 status=UNVERIFIED → POST `/api/v1/realname/verifications` `{realName, idCardNo}` → 断言 200 + `{providerBizId, livenessUrl}`；mock 公安比对 + 活体通过 → 客户端 GET `/api/v1/realname/verifications/{bizId}` → 断言 200 + `{status: VERIFIED, verifiedAt}`；DB 断言 `realname_profile.status=VERIFIED` + `real_name_enc/id_card_no_enc/id_card_hash` 非 NULL + `verified_at` 已写。

**Acceptance Scenarios**:

1. **Given** ACTIVE 账号 + status=UNVERIFIED + 测试身份证号 `110101199001011237`（GB 11643 末位校验通过）+ 协议已勾选，**When** POST `/api/v1/realname/verifications` `{realName: "张三", idCardNo: "110101199001011237"}`，**Then** 返回 200 + `{providerBizId, livenessUrl}`；DB upsert `realname_profile` 行 status=PENDING + `provider_biz_id` 已写
2. **Given** PENDING 状态 + 阿里云返回 (姓名/号码 比对通过 + 活体通过)，**When** GET `/api/v1/realname/verifications/{providerBizId}`，**Then** 返回 200 + `{status: VERIFIED, verifiedAt}`；DB `realname_profile.status` 转 VERIFIED + `verified_at` 已写
3. **Given** VERIFIED 状态用户回访，**When** GET `/api/v1/realname/me`，**Then** 返回 200 + `{status: VERIFIED, realNameMasked: "*三", idCardMasked: "1**************7"}`（per FR-008 mask 规则）

---

### User Story 2 - 主流程：已实名用户回访 readonly（Priority: P1，并列）

已认证（status=VERIFIED）用户进入实名认证页，**直接显示 readonly 视图**（图 3）：头像 + 用户名 + mask 后的姓名 + mask 后的身份证号。**无录入入口、无活体入口、无 DELETE/编辑通道**（per PRD § 5.10 不可解绑，FR-015）。

**Why this priority**: 防止已认证用户重复发起 / 篡改信息；与 PRD 文案"一经绑定，不支持解绑"对齐；阻断恶意试探（如撞库者用既有账号反复测试）。

**Independent Test**: 预设 VERIFIED 账号 → GET `/api/v1/realname/me` → 断言 200 + mask 字段 + 不返回明文 + 响应 schema 不包含 "未实名"分支字段；POST `/api/v1/realname/verifications` 任何 body → 断言 409 `REALNAME_ALREADY_VERIFIED`；不存在 DELETE endpoint（404 by Spring routing）。

**Acceptance Scenarios**:

1. **Given** 账号 status=VERIFIED，**When** GET `/api/v1/realname/me`，**Then** 返回 200 + `{status: VERIFIED, realNameMasked, idCardMasked, verifiedAt}`；**响应中明文姓名 / 身份证号字段不存在**
2. **Given** 账号 status=VERIFIED，**When** POST `/api/v1/realname/verifications` `{realName, idCardNo}`，**Then** 返回 409 `REALNAME_ALREADY_VERIFIED`；DB 行不变
3. **Given** 客户端尝试 DELETE `/api/v1/realname/me` 或类似变体，**Then** 404 / 405（无该路由）

---

### User Story 3 - 异常：公安比对 / 活体失败可重试（Priority: P1，并列）

用户在录入页填错姓名 / 身份证号（公安比对不通过），或活体 SDK 失败 / 取消，状态转 FAILED；用户可在 rate limit 阈值内重试。每次重试走完整 init → SDK → 查结果链路。

**Why this priority**: 真实业务场景下用户输入错误 / 活体失败比例不低；UX 不允许"一次失败永久锁死"。同时必须配限流防爆刷（公安比对 API 计费 + 防身份盗用试探）。

**Independent Test**: 预设 FAILED 状态 + retry_count_24h=2；POST `/verifications` 新姓名 / 身份证号 → mock 阿里云返回比对通过 → 状态转 PENDING → VERIFIED；retry_count_24h reset 到 0（成功后清零）。

**Acceptance Scenarios**:

1. **Given** UNVERIFIED 账号，**When** POST `/verifications` 但姓名与身份证号不匹配（mock 阿里云返回 NameIdNotMatch），**Then** 状态转 FAILED + `failed_reason=NAME_ID_MISMATCH`；客户端 GET `/verifications/{bizId}` → 200 + `{status: FAILED, failedReason: NAME_ID_MISMATCH}`；HTTP 是 200（业务结果）非 422
2. **Given** FAILED 账号，**When** POST `/verifications` 新尝试 + retry_count_24h < 5，**Then** 200 + 新 `providerBizId`；旧 PENDING/FAILED 行被覆盖（`updated_at` 更新）
3. **Given** SDK 上报活体失败 / 用户取消（webhook 通知 / 客户端轮询），**Then** 状态转 FAILED + `failed_reason ∈ {LIVENESS_FAILED, USER_CANCELED}`
4. **Given** retry_count_24h ≥ 5，**When** POST `/verifications`，**Then** 429 `RATE_LIMIT_EXCEEDED` + `Retry-After`（per FR-009）

---

### User Story 4 - 异常：身份证号已被其他账号绑定（Priority: P2）

用户 A 已实名认证（VERIFIED）；用户 B 尝试用同一身份证号 → 拒绝 + `REALNAME_ID_CARD_OCCUPIED`。

**Why this priority**: 防一证多账号（业务侧避免同人多号灰产）；DB unique constraint 兜底但服务层提前拦截给出明确错误。

**Independent Test**: 账号 A `realname_profile.id_card_hash=H` 且 status=VERIFIED；账号 B 提交相同身份证号 → 服务端计算 hash 命中 → 409 `REALNAME_ID_CARD_OCCUPIED`，账号 B `realname_profile` 不创建。

**Acceptance Scenarios**:

1. **Given** 账号 A 已 VERIFIED + id_card_hash=H，**When** 账号 B POST `/verifications` 同身份证号，**Then** 409 `REALNAME_ID_CARD_OCCUPIED`；账号 B 不写库；不调阿里云 API
2. **Given** 账号 A 后续被注销 → 进入 ANONYMIZED → `realname_profile.id_card_hash` NULL（per PRD § 5.5 amend），**When** 账号 B 再次提交相同身份证号，**Then** 200 正常 init（身份证号已释放）
3. **Given** 账号 A 当前 PENDING（init 但未完成）+ id_card_hash=H，**When** 账号 B POST 同号，**Then** 同 1 拒绝

---

### Edge Cases

- **身份证号格式异常**：长度非 18 / 含非法字符 / GB 11643 末位校验码不通过 → 400 `REALNAME_INVALID_ID_CARD_FORMAT`（前端正则 + 后端兜底）
- **协议未勾选**：前端「下一步」按钮 disabled；后端兜底 — 未携带 agreement 标志 / `agreementVersion` → 400 `REALNAME_AGREEMENT_REQUIRED`
- **FROZEN 账号尝试发起认证**：account.status=FROZEN（注销冻结期）→ 拒绝（与 PRD § 5.5 冻结期行为表对齐：实名认证视为"修改资料"类操作）；返回 403 `ACCOUNT_IN_FREEZE_PERIOD`（既有错误码，per PRD § 7）
- **ANONYMIZED 账号**：account.status=ANONYMIZED 不能登录（per phone-sms-auth FR-005），故无法到达本 endpoint；不专门处理
- **阿里云 init 失败**（网络超时 / 上游业务错误）：503 `REALNAME_PROVIDER_TIMEOUT` 或 502 `REALNAME_PROVIDER_ERROR`；不写 PENDING 行（事务回滚）；客户端可重试
- **PENDING 行长期未完成**（用户中途放弃 + 未触发 SDK 回调）：≥ 30min 视为超时 → 后台定时任务转 FAILED + `failed_reason=USER_CANCELED`（M1 commented TODO，由埋点统一接入时落地）；M1 期内由用户重新提交时覆盖旧行
- **客户端轮询过频**：`/verifications/{bizId}` 由 `realname:<accountId>` 限流兜底
- **身份证号 hash 碰撞**：SHA-256 + per-deployment pepper，理论冲突极低；冲突时回退为应用层比对明文（解密+对比，发生在 `id_card_hash` 命中时再 double-check）— per CL-007

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001（Endpoints）**：本 use case 暴露 3 个 endpoint，所有路径前缀 `/api/v1/realname`：
  - `GET /api/v1/realname/me` — 查询当前账号实名状态（用于客户端进入实名页时分支 readonly vs 录入）
  - `POST /api/v1/realname/verifications` — 发起实名认证（入参 `{realName, idCardNo, agreementVersion}`，返回 `{providerBizId, livenessUrl}`）
  - `GET /api/v1/realname/verifications/{providerBizId}` — 查询 specific verification 状态（客户端轮询活体结果）
  - 鉴权：均要求 `Authorization: Bearer <access_token>`；未授权 → 401 `UNAUTHORIZED`（既有 mbw-shared 兜底）
- **FR-002（身份证号格式校验）**：
  - 18 位字符；前 17 位为数字；末位为 0-9 或 X
  - GB 11643-1999 末位校验码计算（前 17 位加权求和 mod 11，查表）
  - 校验失败 → 400 `REALNAME_INVALID_ID_CARD_FORMAT`
  - 格式校验**先于**任何加密 / 阿里云调用
- **FR-003（状态机）**：`RealnameProfile.status` ∈ {UNVERIFIED, PENDING, VERIFIED, FAILED}；状态转换规则与 PRD § 5.10 状态机图一致：
  - UNVERIFIED → PENDING（POST `/verifications` 成功 init）
  - PENDING → VERIFIED（阿里云返回比对+活体通过）
  - PENDING → FAILED（阿里云返回任一不通过 / SDK 失败 / 超时）
  - FAILED → PENDING（用户重试，rate limit 内）
  - VERIFIED 终态，无任何转出转换；任何 POST `/verifications` 返回 409 `REALNAME_ALREADY_VERIFIED`
- **FR-004（加密存储）**：
  - 真实姓名 + 身份证号入库前 AES-GCM 加密（IV 随机 + auth tag）；DB 字段 `bytea`
  - 主密钥（master key）通过 KMS 托管；M1 阶段以 env var `MBW_REALNAME_DEK_BASE64` 注入 32-byte DEK；M3 上线前切换阿里云 KMS（per CL-006）
  - 加密层抽象 `CipherService.encrypt/decrypt(byte[])` 接口；M1 实现 `EnvDekCipherService`，M3 切 `AliyunKmsCipherService`
  - 解密仅在三处发生：(1) 服务端 SHA-256 hash 碰撞时 double-check；(2) M2 客服后台核身工单读取（M2 后做）；(3) 不会发生在 client-facing 响应路径
- **FR-005（id_card_hash UNIQUE）**：
  - DB column `id_card_hash CHAR(64) UNIQUE`，值 = SHA-256(身份证号 || pepper)；pepper 通过 env var `MBW_REALNAME_HASH_PEPPER` 注入（≥ 32 字节随机字符串）
  - POST `/verifications` 流程：计算 hash → 查 `realname_profile WHERE id_card_hash=? AND account_id != current` → 命中 → 409 `REALNAME_ID_CARD_OCCUPIED`，**不调阿里云**
  - DB constraint 兜底：违反 UNIQUE 时也返回 409 `REALNAME_ID_CARD_OCCUPIED`
- **FR-006（阿里云 init 调用 + bizId 幂等）**：
  - 调用阿里云实人认证 OpenAPI（产品名候选 `Cloud_authentication` / `Real-name verification`，具体 endpoint 由 plan.md 选定）
  - 服务端生成 `providerBizId`（UUID v4，幂等键），调用前写 `realname_profile.provider_biz_id`，再调阿里云
  - 阿里云返回的 livenessUrl 透传给客户端
  - 阿里云 5xx / 超时 → 503 `REALNAME_PROVIDER_TIMEOUT`；阿里云业务错误（参数校验失败等）→ 502 `REALNAME_PROVIDER_ERROR`；事务回滚不写 PENDING 行
- **FR-007（公安比对 + 活体回调）**：
  - 客户端 SDK 完成活体后**仅返回成功 / 失败 signal**（per 阿里云 SDK 规范）；权威结果由服务端从阿里云查询
  - `GET /verifications/{providerBizId}` 内部调阿里云 `DescribeVerifyResult` 类似接口取最终结果（公安比对 + 活体）
  - 结果分支：
    - 比对通过 + 活体通过 → 写 `realname_profile.status=VERIFIED, verified_at=now()`
    - 比对不通过 → status=FAILED + failed_reason=NAME_ID_MISMATCH
    - 活体不通过 → status=FAILED + failed_reason=LIVENESS_FAILED
    - SDK 用户取消 → status=FAILED + failed_reason=USER_CANCELED
    - 阿里云 5xx → status 不变（保持 PENDING），返回 503 `REALNAME_PROVIDER_TIMEOUT`，客户端重试
  - 查询路径**幂等**：同一 bizId 重复 GET 返回相同结果（已写 VERIFIED/FAILED 后从 DB 直接读，不再调阿里云）
- **FR-008（mask 显示）**：所有 client-facing 响应中包含真实姓名 / 身份证号字段时，必须为 mask 形态：
  - `realNameMasked`：保留姓氏 + 末字，中间 `*` 填充至 ≥ 2 个 `*`；2 字姓名（如"张三"）→ `*三`；3 字（"张小明"）→ `*小明` → `*\*\*明`（保留尾字）；4+ 字 → `*` 填中间
  - `idCardMasked`：保留首位 + 末位，中间 16 字符 `*`；如 `110101199001011237` → `1***************7`
  - 非 mask 字段（明文）**永不出现在响应 body**
- **FR-009（限流）**：
  - `realname:<accountId>` 24h 滚动窗口内 5 次失败（FAILED transition）→ 第 6 次 POST `/verifications` 返回 429 `RATE_LIMIT_EXCEEDED` + `Retry-After`（24h）
  - `realname:<ip>` 24h 滚动窗口内 20 次（任何 status transition 计数）→ 第 21 次 429
  - `RateLimitService` 复用既有 Redis backend（per ADR-0011）；新增 `realname:` bucket 命名空间
  - 成功 transition（VERIFIED）后 `realname:<accountId>` 计数 reset 到 0
- **FR-010（dev-bypass env hatch）**：
  - env var `MBW_REALNAME_DEV_BYPASS=true` + `MBW_REALNAME_DEV_FIXED_RESULT=verified|failed`
  - 启用时：POST `/verifications` 不调阿里云，直接根据 `_FIXED_RESULT` 写 PENDING → VERIFIED 或 FAILED；姓名 / 身份证号仍按正常流程加密入库
  - GET `/verifications/{bizId}` 返回固定结果
  - 默认测试身份证号 `110101199001011237`（GB 11643 校验通过）
  - **prod 环境严禁启用**：`@ConditionalOnProperty` + 启动 fail-fast：`MBW_REALNAME_DEV_BYPASS=true` 且 `spring.profiles.active=prod` 时启动失败
  - 与既有 phone-sms `dev-fixed-code` env hatch 同套路（per server commit 22c2135 / PR #146 / #148）
- **FR-011（协议授权占位）**：
  - 入参 `agreementVersion: string`（如 `"v1.0-placeholder"`）；服务端校验非空 → 写 `account_agreement` 表（既有，per PRD § 5.7）；空 / null → 400 `REALNAME_AGREEMENT_REQUIRED`
  - 协议文本 M1 阶段为前端 placeholder 静态页；后端不持有协议文本，仅记录版本号 + 同意时间
  - M3 法务定稿后协议版本号升级（如 `"v2.0-final"`）；旧版本仍接受（向前兼容）
- **FR-012（FROZEN 期间禁止）**：
  - POST `/verifications` 时校验 `account.status`；FROZEN → 403 `ACCOUNT_IN_FREEZE_PERIOD`（既有错误码 per PRD § 7）+ `freezeUntil` 字段
  - GET `/me` 仍允许查询（仅返回 status，不携带敏感信息）— 用户在冻结期撤回注销后能立即查看实名状态
  - GET `/verifications/{bizId}` 同上 GET `/me` 处理（仅查询，无状态变更）
- **FR-013（错误码完整集）**：所有错误响应符合 RFC 9457 ProblemDetail（`application/problem+json`），由 `mbw-shared.web.GlobalExceptionHandler` 映射；本 use case 涉及的错误码：
  - `REALNAME_INVALID_ID_CARD_FORMAT` 400
  - `REALNAME_AGREEMENT_REQUIRED` 400
  - `REALNAME_ALREADY_VERIFIED` 409
  - `REALNAME_ID_CARD_OCCUPIED` 409
  - `REALNAME_NAME_ID_MISMATCH` 422（含义：非阻断错误，仅作为 GET `/verifications/{bizId}` 的 `failed_reason` 字段值；FR-007 中已声明 GET 返回 200 + business result）
  - `REALNAME_LIVENESS_FAILED` 422（同上 `failed_reason` 字段值）
  - `REALNAME_PROVIDER_ERROR` 502
  - `REALNAME_PROVIDER_TIMEOUT` 503
  - `ACCOUNT_IN_FREEZE_PERIOD` 403（既有，复用）
  - `RATE_LIMIT_EXCEEDED` 429（既有，复用）
  - `UNAUTHORIZED` 401（既有，由 Spring Security 兜底）
- **FR-014（日志禁明文）**：
  - 真实姓名 / 身份证号 / 身份证号 hash **永不出现**在任何日志（INFO/WARN/ERROR/DEBUG/异常栈）
  - 仅允许日志：`accountId` / `providerBizId` / `status transition` / `failed_reason` enum / 错误码
  - 由 `LoggingSafeRealnameProfile` 工具类 + Lombok `@ToString.Exclude` 双层防御
  - 配套 `LoggingLeakIT` 集成测试：跑完整流程后 grep logs 不命中身份证号 / 姓名明文
- **FR-015（不可解绑）**：
  - **无 DELETE `/api/v1/realname/me` endpoint**；尝试调用返回 404 / 405 by Spring routing
  - **无 PATCH / PUT** 修改 `realname_profile` 的 endpoint；M2 后由管理员后台路径补
  - 与 PRD § 5.10 不变式 1 对齐
- **FR-016（埋点 placeholder）**：
  - POST `/verifications` 成功 → 埋点 `realname_init`（per PRD § 5.9 amend）
  - status transition 终态（VERIFIED / FAILED）→ 埋点 `realname_complete` + `result + failed_reason`
  - 本 use case 仅声明事件名 + 字段；埋点接入由埋点模块统一处理（M2+）；本期 commented `// TODO: emit telemetry realname_init / realname_complete`

### Key Entities

- **RealnameProfile（聚合根，新增）**：
  - `id`、`accountId`（UNIQUE FK → Account）、`status`、`realNameEnc`（bytea）、`idCardNoEnc`（bytea）、`idCardHash`（CHAR(64) UNIQUE）、`providerBizId`、`verifiedAt`、`failedReason`（enum）、`failedAt`、`retryCount24h`、`createdAt`、`updatedAt`
  - 与 Account 关系：one-to-one（每账号最多一行；UNIQUE on `account_id`）
  - 同 schema `account` 下；不与 Account 表 FK（cross-row 引用走 ID，per modular-strategy.md "禁止跨 schema FK"，但同 schema 内可加 FK；本 entity 选择**不加 FK** — 与既有 `LoginAudit` / `AccountAgreement` 风格一致，便于未来拆服务）
- **既有 Account 不变**：本 use case 不引入 `Account` 字段变化；`status` 字段的 FROZEN/ANONYMIZED 检查复用既有 `AccountStateMachine`

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**：主流程 P95 ≤ 800ms（per PRD § 6.1 L3 含三方 SLO，不含 SDK 活体阶段；从 `POST /verifications` 入到响应、`GET /verifications/{bizId}` 入到响应）
- **SC-002（敏感信息保密）**：跑完整 User Stories 1-4 端到端后：
  - DB `realname_profile.real_name_enc / id_card_no_enc` 是 AES-GCM ciphertext，明文 grep 命中 0 次
  - 应用日志（dev/staging/prod 三档 logger config）跑完整流程后 grep 身份证号 / 姓名明文命中 0 次（由 `LoggingLeakIT` 验证）
  - response body grep 身份证号 / 姓名明文命中 0 次（仅 mask 形态出现）
- **SC-003（同号唯一）**：100 个账号并发提交相同身份证号 → DB `realname_profile` 总行数 ≤ 1（其中 status=VERIFIED 或 PENDING 的 0/1 行）；其余 99+ 请求返回 409
- **SC-004（dev-bypass 通路）**：CI 环境（无阿里云密钥）跑全套集成测试通过率 100%；`MBW_REALNAME_DEV_BYPASS=true` 启用下覆盖 User Stories 1-4 全部场景
- **SC-005（限流准确性）**：`realname:<accountId>` 24h 5 次 + `realname:<ip>` 24h 20 次两条规则的集成测试验证生效，命中后正确返回 429 + `Retry-After`
- **SC-006（API 契约）**：OpenAPI spec 通过 Springdoc 自动生成包含 3 个 endpoint + 完整 schema + 错误码示例；前端 `pnpm api:gen` 拉新 spec 后类型自动同步
- **SC-007（不可解绑）**：DELETE / PATCH `/api/v1/realname/me` 在 OpenAPI spec 中不存在；CI 集成测试 `RealnameImmutabilityIT` 验证 404/405

## Clarifications

> 7 点澄清于 2026-05-08 与 PR #65 PRD 扩展同期完成。

### CL-001：v1 仅大陆二代证

**Q**：是否 v1 即支持港澳台 / 护照 / 一代证？

**A**：**仅大陆二代证**（用户决策 2026-05-08）。理由：(1) 阿里云实人认证大陆二代证 API 最成熟、稳定；(2) 港澳台 / 护照需独立产品 + 独立审批流；(3) 真实用户分布预计大陆 ≥ 95%（per M1 受众）。M2+ 评估扩展。

**落点**：FR-002 限定 18 位 + GB 11643；图 1 mockup 中"非中国大陆二代身份证"入口在 v1 隐藏（user 红字标注 1）；Out of Scope 加"非大陆证件版面"。

### CL-002：VERIFIED 终态不可解绑（M1 锁死，M2 客服核身）

**Q**：用户输错信息或换证（一代→二代升级）后是否允许修改？

**A**：**M1 完全锁死**（用户决策 2026-05-08）；M2 起补管理员后台核身工单（基于客服 case，非自助）。

**落点**：FR-015 显式无 DELETE / PATCH endpoint；FR-003 VERIFIED 无转出 transition；图 3 readonly 视图无编辑入口；Out of Scope 加"自助修改通道"。

### CL-003：协议《实名认证服务个人信息处理授权协议》placeholder

**Q**：M1 阶段协议文本是否需要法务定稿？

**A**：**M1 placeholder，M3 真用户前定稿**（用户决策 2026-05-08）。后端只记录 `agreementVersion` + `agreedAt`（写入既有 `account_agreement` 表，per PRD § 5.7）；前端 placeholder 静态页（与 `legal/` 目录套路一致）。

**落点**：FR-011；前端 spec 标 placeholder TODO；Assumptions 加"协议文本 M3 定稿"。

### CL-004：阿里云实人认证（vs 腾讯云慧眼 vs 全 mock）

**Q**：v1 是否接真供应商？哪家？

**A**：**阿里云实人认证**（用户决策 2026-05-08）。理由：与既有阿里云 SMS（per ADR-0013 待商业牌照后启用）/ 阿里云 OSS / 阿里云 ECS 生态对齐；单云供应商减少 IAM 复杂度；典型 0.5-1 元/次成本可接受（M1 阶段调用量预估 < 1000 次/月）。

**落点**：FR-006 / FR-007 阿里云 OpenAPI；plan.md 选定具体产品名（`Cloud_authentication` 或 `Real-name verification` 二选一，待 plan 阶段查阿里云 console）；ADR — 写在 plan.md 内部决策段（per constitution "use-case-internal decisions stay in plan.md"）。

### CL-005：dev-bypass 与 phone-sms `dev-fixed-code` 同套路

**Q**：dev / 单测如何不烧阿里云 API 费用？

**A**：**复用 phone-sms `dev-fixed-code` 模式**（推断 + 默认）：env var `MBW_REALNAME_DEV_BYPASS=true` 启用 + `_FIXED_RESULT` 控制结果；prod 环境启动 fail-fast 阻止误用。

**落点**：FR-010；plan.md 实现层 mock `AliyunRealnameClient` + `BypassRealnameClient` 双 implementation by `@Profile`。

### CL-006：KMS 策略 — M1 env-bound DEK / M3 阿里云 KMS

**Q**：实名信息加密 master key 怎么管？

**A**：**M1 env-bound DEK（`MBW_REALNAME_DEK_BASE64`）→ M3 上线前切阿里云 KMS**（默认）。理由：M1 阶段无真用户、单 ECS 部署；env var 注入足够；M3 内测 → 真用户进入前升级到 KMS 集中管理 + 自动轮换。

**落点**：FR-004；plan.md 抽象 `CipherService` 接口 + 双 impl；Out of Scope 加"阿里云 KMS 接入 M3"。

### CL-007：id_card_hash UNIQUE 防同号多绑

**Q**：是否允许同一身份证号绑多账号？

**A**：**至多一个 account**（默认；与主流产品一致：微博 / 抖音 / 知乎实名均强制一证一号）。DB UNIQUE 兜底 + 服务层提前拦截 409 `REALNAME_ID_CARD_OCCUPIED`。

**落点**：FR-005；SC-003 验证；Edge Cases 含"hash 碰撞极低概率回退"。

## Assumptions

- **A-001**：阿里云实人认证已在阿里云控制台开通（M1.X 实施前由 owner 完成）；access key + secret 通过 env var `MBW_ALIYUN_REALNAME_ACCESS_KEY_ID` / `_SECRET` 注入
- **A-002**：DEK 与 hash pepper 通过 env var 注入；dev / staging / prod 三档独立配置；启动 fail-fast：env var 缺失则启动失败
- **A-003**：复用既有 `RateLimitService` Redis backend（per ADR-0011）+ `mbw-shared.web.GlobalExceptionHandler` ProblemDetail 映射 + `account_agreement` 表（per PRD § 5.7）
- **A-004**：OpenAPI 单一真相源（per server CLAUDE.md § 六）；spec.md 不重复 API data contract，仅写 endpoint 列表 + 行为；data schema 由 Springdoc 注解生成
- **A-005**：测试号身份证 `110101199001011237` 在 GB 11643 末位校验下通过；阿里云 mock（WireMock / 默认 dev-bypass）按需返回 verified / failed
- **A-006**：`realname_profile` 表归属 `account` schema；新建 Flyway migration `V<n>__create_realname_profile.sql`（version number 由 plan 阶段定）
- **A-007**：本 use case 不引入新 Spring Modulith Event；M2+ 接 `mbw-billing.EntitlementApi` 时再加 `RealnameVerifiedEvent`

## Out of Scope

- **非中国大陆二代身份证**（港澳台 / 护照 / 一代证 / 军官证）— M2+ 评估
- **未成年人保护强校验**（如年龄段二次告警 / 监护人同意流程）— M2+ 评估
- **自助修改通道**（用户输错或换证后改名）— M2 客服核身工单替代（管理员后台路径）
- **下游 gate 接入**（提现 / 达人入驻 / 找回账号）— M2+ 接入；本 use case 仅暴露 `IdentityApi.isVerified(accountId)` 跨模块查询接口（具体 plan 决定是否本期就暴露 api）
- **阿里云 KMS 接入** — M3 上线前补；M1 阶段 env-bound DEK
- **后台管理员核身 UI** — M2 后由独立 admin spec 处理
- **PENDING 超时定时清理任务** — M1 commented TODO，M2 与埋点统一接入时落地
- **OCR 自动识别身份证照片** — 永不规划（用户输入即可，OCR 增加复杂度无显著收益）
- **离线核身 / 二要素纯文本比对（无活体）** — 拒绝（per CL-004 v1 即三要素 + 实人活体）
- **审计日志 / 合规审查导出** — M3 合规检查阶段评估

## References

- [PRD account-center.v2 § 5.10](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#510-实名认证m1x-引入)（meta PR #65 ship 2026-05-08）
- [PRD § 5.5 账号注销](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#55-账号注销)（匿名化字段表含 realname_profile）
- [PRD § 5.7 合规](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#57-合规--数据出境)（`account_agreement` 表）
- [PRD § 6.4 安全基线](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#64-安全基线)（AES-GCM + KMS 约束）
- [PRD § 7 错误码表](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#7-错误码选摘)（8 条 `REALNAME_*` 已登记）
- [ADR-0011](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0011-rate-limit-jcache-then-redis.md) RateLimit 复用
- [ADR-0013](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0013-defer-sms-to-business-license.md) Aliyun ESP（同 vendor 生态参考）
- [ADR-0017](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0017-sdd-business-flow-first-then-mockup.md) 前端类 1 标准 UI 流程（前端 spec 用）
- 前端 spec：[`apps/native/spec/realname/`](https://github.com/xiaocaishen-michael/no-vain-years-app/tree/main/apps/native/spec/realname)（待开 PR）
- 既有参考 spec：[`spec/account/phone-sms-auth/`](../phone-sms-auth/) — 结构参照
