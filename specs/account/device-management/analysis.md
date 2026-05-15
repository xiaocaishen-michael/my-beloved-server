# Analysis: Device Management

**Spec**: [`./spec.md`](./spec.md) · **Plan**: [`./plan.md`](./plan.md) · **Tasks**: [`./tasks.md`](./tasks.md)
**Created**: 2026-05-08
**Reviewer**: SDD `/speckit.analyze` 等价的人工跨件套一致性扫描（spec-kit slash 命令未装，per memory `reference_spec_kit_app_repo_not_installed.md` server 仓 .specify 模板已装但 .claude/commands/ 空）

> 与 [`../../auth/refresh-token/`](../../auth/refresh-token/) + [`../delete-account/`](../delete-account/) + [`../cancel-deletion/`](../cancel-deletion/) 联合阅读以确认 token lifecycle 闭环不退化。

## Coverage Matrix（FR / SC → Plan / Task / 测试）

| FR | Plan 段 | Task | 测试覆盖 |
|---|---|---|---|
| FR-001 list endpoint | § Endpoint 1 | T14 | T14 unit + T15 E2E |
| FR-002 list item schema | § 流程 step 4 | T10 + T14 | T11 unit（DeviceItem 结构）+ T15 E2E（response 校验）|
| FR-003 revoke endpoint（含幂等）| § Endpoint 2 | T14 | T14 unit + T15 E2E + T16 concurrency |
| FR-004 本机识别 | § Endpoint 1 流程 step 4 | T11 | T11 should_return_paginated_items_with_isCurrent_flag |
| FR-005 拒删当前 device | § Endpoint 2 流程 step 4 | T12 | T12 should_throw_CannotRemoveCurrentDevice + T15 US3 AS1 |
| FR-006 缺 did claim 拦截 | § plan 流程 step 1 + JwtAuthFilter | T3 + T14 | T3 JwtTokenIssuer + T14 should_return_401_when_did_claim_missing + T15 US3 AS2 |
| FR-007 schema 5 列 | § V11 migration | T4 | T5 IT 间接覆盖（schema 不对齐启动失败）|
| FR-008 access token claim 扩展 | § 既有 5 UseCase wiring | T3 + T9 | T3 JwtTokenIssuer + T9 wiring + T17 cross-spec regression |
| FR-009 client header 上报 | § DeviceMetadataExtractor | T7 | T7 utility 单测 + T15 E2E（header 通路）|
| FR-010 IP 提取 + 私网过滤 | § DeviceMetadataExtractor | T7 | T7 should_skip_private_ips_in_xff |
| FR-011 GeoIP 解析 | § Ip2RegionService + Adapter | T2 + T8 | T8 IT（5 IP 命中 + 私网空 + null）|
| FR-012 rotation 元数据继承 | § 既有 5 UseCase wiring（特殊）| T9 | T9 should_inherit_device_metadata_from_old_row + T17 should_inherit_4_fields_and_use_new_ip |
| FR-013 限流 4 维度 | § 流程限流键 | T11 + T12 | T11 + T12 unit + T15 US5 |
| FR-014 错误响应（含 ProblemDetail）| § Web layer GlobalExceptionHandler | T14 | T14 unit + T15 E2E |
| FR-015 OpenAPI 暴露 | § API 契约 | T18 | OpenApiSnapshotIT |
| FR-016 既有 endpoint schema 不动 | § plan 反模式段 | T9 + T17 | T17 should_keep_phone_sms_auth_response_byte_compatible |
| FR-017 DeviceRevokedEvent | § 事件流 | T13 + T12 | T12 publishEvent 调用断言 + outbox IT |
| FR-018 埋点 placeholder | § plan 不强制 | T12 | T12 should_log_INFO_with_short_deviceId_no_token_hash |

| SC | Task |
|---|---|
| SC-001 list P95 ≤ 200ms | 不显式 IT，靠监控（与 phone-sms-auth pattern 一致）|
| SC-002 revoke P95 ≤ 100ms | 同上 |
| SC-003 revoke 原子性 | T16 should_keep_state_unchanged_when_outbox_publish_fails |
| SC-004 本机识别正确性 | T11 + T15 US1 + T17 |
| SC-005 拒删当前 device | T12 + T15 US3 AS1 |
| SC-006 rotation 元数据传递 | T17 should_inherit_4_fields_and_use_new_ip |
| SC-007 反枚举跨账号 404 | T12 should_throw_DeviceNotFound_when_recordId_belongs_to_other_account + T15 US2 AS5 |
| SC-008 限流准确性 | T11 + T12 unit + T15 US5 |
| SC-009 GeoIP 准确性 | T8 IT（5 IP 北上广深杭）|
| SC-010 缺 did claim 拦截 | T3 + T14 + T15 US3 AS2 |
| SC-011 ArchUnit / Modulith Verifier | 既有 ModuleStructureTest |
| SC-012 OpenAPI snapshot | T18 |
| SC-013 token lifecycle 不退化 | T17 cross-spec regression（5 既有 IT 全绿）|

**Coverage 评估**：所有 FR / SC 均有 task + 测试覆盖；性能 SLO 不强制 IT，与 phone-sms-auth/cancel-deletion/delete-account 一致 pattern。

## Constitution Compliance

| 原则 | 检查 | 结果 |
|---|---|---|
| Modular Monolith | 仅 mbw-account 内改动；DeviceType / LoginMethod / DeviceRevokedEvent 在 api 包跨模块可见 | ✅ |
| DDD 5-Layer | 严格分层（值对象 + Repository + Adapter + UseCase + Controller） | ✅ |
| TDD Strict | 每 task 红绿循环；record/enum 例外明示（per CLAUDE.md § 一） | ✅ |
| Repository pattern | domain 接口 + JpaEntity + Mapper + RepositoryImpl 既有 4 件套扩展 | ✅ |
| No cross-schema FK | device_id 无 FK | ✅ |
| Flyway immutable | V11 单文件 + 后续 migration 必须新文件 | ✅ |
| expand-migrate-contract 跳步 | 单 PR 落 5 列 — M3 内测前条件满足 + plan 内显式声明跳步理由 | ✅（条件性合规）|
| OpenAPI 单一真相源 | spec.md 不重复 OpenAPI 字节；T18 snapshot 自动覆盖 | ✅ |
| No password / token in logs | T12 显式断言 short deviceId / no token_hash | ✅ |
| spec.md 3 段官方模板 | spec User Scenarios / Functional Requirements / Success Criteria | ✅ |
| Anti-pattern 反 spec drift | rotation 元数据继承 / login_method 不引 REFRESH 值 / list 不暴 raw IP — 全部明示在 plan 反模式段 | ✅ |

## Findings

| 严重度 | ID | Location | 描述 | 建议 |
|---|---|---|---|---|
| MEDIUM | F-001 | spec FR-006 + plan T9 | client 升级到带 did claim 后,**老 access token（无 did claim）调 device-management endpoint 立即 401** — 客户端被迫重 login 一次 | 文档化 + 客户端 401-refresh 中间件兜底（既有）。**alt 方案**（不采）：spec 加过渡期 fallback，让缺 did 的 access token 仍可调 list 但返 isCurrent=false（破坏 FR-005 可被 hack）。当前方案接受过渡期一次重 login |
| MEDIUM | F-002 | spec CL-002 + plan response | list response 不暴 raw ipAddress，只暴 location；未来若引入"高级安全模式"用户希望看 raw IP → 需新 admin endpoint | 接受 — single-user 阶段无该需求；M3 内测后评估 admin / 高级模式 |
| LOW | F-003 | spec FR-009 + plan T7 | client 缺 X-Device-Id header → server 生成 UUID v4 落库 — 攻击者大量请求会创出 N 条孤儿 row | 限流 + access token 鉴权前置防御；攻击成本高于收益。M3 内测前可改为 400 强校验（per CL-001 落点）|
| LOW | F-004 | spec FR-008 + plan T9 | rotation 路径继承旧 row 的 deviceId / deviceName / deviceType / loginMethod；若旧 row 是 V11 之前的（device_id IS NULL）→ 怎么办？| **当前 M1.X 不可达** — V11 之前无生产用户，所有 row 都是 V11 之后写的。**未来 M3+ 数据迁移期**：plan 应加一次性 backfill SQL（V12）补齐既有 NULL device_id |
| LOW | F-005 | plan T8 ip2region.xdb | 二进制资源 ~5MB 入 git — 仓体积增加；后续 .xdb 升级版本如何 | 接受 — 一次性 cost；后续升级走新 V?? migration 触发重加载（应用启动时从 classpath 加载，更新只需新 jar）|
| LOW | F-006 | spec FR-013 限流键 | 当前 4 限流键基于 accountId / IP；多账号共用同 IP（家庭 / 公司网络）会被 IP 维度限流误伤 | 接受 — IP 维度阈值（list 100/min / DELETE 20/min）已较宽松；M3+ 真用户场景如有误伤再调阈值 |
| INFO | F-007 | spec FR-017 | DeviceRevokedEvent 当前无 listener 消费 | 接受 — outbox 设计预留 M2+ 安全审计 / 通知模块订阅；与 cancel-deletion AccountDeletionCancelledEvent / delete-account AccountDeletionRequestedEvent 对称 |
| INFO | F-008 | plan T9 既有 5 UseCase wiring | `LoginByPasswordUseCase` 是否仍存在（项目可能已删）— 若已删 task 减一 | impl 阶段 grep 确认；删了不影响其他 wiring |
| INFO | F-009 | spec out of scope last_used_at | 「最近活跃」精度提升到秒级需新增 last_used_at 列 + 每个业务 endpoint UPDATE，性能代价大；M3+ 评估 | 接受 — 当前 created_at 精度（≤ 15 min access token TTL）够用 |

**无 CRITICAL / HIGH** finding。

MEDIUM F-001 是被 spec FR-006 显式接受的过渡期成本，已文档化 + 401-refresh 中间件兜底；不视为阻塞项。

MEDIUM F-002 是 CL-002 显式落点决议，文档化处理；不视为阻塞。

## CRITICAL / HIGH 修正项汇总

无。

## 与既有 spec 的协同

### 与 [`../../auth/refresh-token/`](../../auth/refresh-token/)（核心依赖）

| 协同点 | 本 spec 影响 | 既有 spec 影响 |
|---|---|---|
| `RefreshTokenRecord` 聚合根扩展 5 字段 | 新增 deviceId / deviceName / deviceType / ipAddress / loginMethod | 既有 schema 兼容（V11 加列不破现有数据）|
| rotation-on-each-use 路径 | 新 row 继承旧 row 4 字段 + IP 用新值（FR-012）| 既有 rotation 行为不变 — IT 必须 GREEN（T17）|
| `findByTokenHash` / `save` / `revokeAllForAccount` | 不动 | 既有 |
| 新增 `findActiveByAccountId(AccountId, Pageable)` | 走 V11 partial index | 既有 |
| `JwtTokenIssuer.signAccess` 改签名 | 删旧签名 + 新签名带 deviceId | refresh-token impl 入参 + JwtAuthFilter 解 did claim 同步改 |

### 与 [`../delete-account/`](../delete-account/) / [`../cancel-deletion/`](../cancel-deletion/)

| 协同点 | 本 spec 影响 | 既有 spec 影响 |
|---|---|---|
| `LogoutAllUseCase` 不签 token | 不影响 | 不动 |
| `SendDeleteAccountCodeUseCase` / `SendCancelDeletionCodeUseCase` 不签 token | 不影响 | 不动 |
| `DeleteAccountUseCase` 调 `RefreshTokenRepository.revokeAllForAccount` | revoke 后所有 row 标 revoked_at；本 spec list 不会显示已 revoked row（partial index 过滤）| 行为不变 |
| `CancelDeletionUseCase` 转 ACTIVE 后签新 token | 新 row 写 5 字段（input header）；调用 `JwtTokenIssuer.signAccess(accountId, deviceId, now)` | T9 wiring 接入 |
| `ACCOUNT_IN_FREEZE_PERIOD` 拦截链（spec D 已 ship）| device-management endpoint 都需 access token；FROZEN 账号会被既有 SecurityFilter 拦截 → 403 | 不动 |

### 与 [`../phone-sms-auth/`](../phone-sms-auth/) / [`../register-by-phone/`](../register-by-phone/)

| 协同点 | 本 spec 影响 | 既有 spec 影响 |
|---|---|---|
| 反枚举 INVALID_CREDENTIALS 字节级一致 | device-management 用独立 problem.type（DEVICE_NOT_FOUND / CANNOT_REMOVE_CURRENT_DEVICE / 401）；不与 phone-sms-auth INVALID_CREDENTIALS 字节级一致（不同语义不同 problem.type）| 不动 |
| LoginResponse schema | 不变 — device-management 不签 token | 不动 |
| 既有 IT 必须 GREEN | T17 cross-spec regression | T17 显式断言 |

## 建议下一步

1. **本 docs PR merge 后**：进入 implementation phase（PR 3）
2. **Implementation 顺序强制**：本 spec impl PR 起在 plan/tasks PR 已 merge 后
3. **共用前置**：refresh-token / phone-sms-auth / cancel-deletion / delete-account / logout-all / account-profile / expose-frozen-account-status 全已 ship
4. **PRD § 5.4 文本同步**：原 PRD 描述"读 LoginAudit"，实际本 spec 改用 refresh_token 表 — meta 仓单独 docs PR 修订（不阻塞本 spec PR）
5. **F-001 客户端文案对齐**：app 仓 device-management 流程 PR 需把 401 响应映射为"会话已失效，请重新登录"通用文案（既有 401-refresh 中间件兜底）
6. **F-008 LoginByPasswordUseCase 存在性确认**：impl 阶段 grep；若已删，T9 wiring task 减一处

## 与 device-management impl PR 的协同（前向预告）

T17 cross-spec regression IT 是本 spec 与既有 token lifecycle 的"contract"。如果 T17 任一既有 IT 退化（如 `PhoneSmsAuthUseCaseTest.should_persist_device_metadata_on_login` GREEN 但 `RefreshTokenRecordRepositoryImplIT.should_persist_5_device_fields` RED），impl 必停 + 修 + 重测 — 不允许 wiring 漏。

**关键不变量**（联合断言）：

- 5 token-issuing UseCase（含 rotation）必须正确写入 5 字段
- access token 必含 `did` claim — 影响 device-management endpoint 鉴权 + JwtAuthFilter 解析
- list response 不暴 ipAddress — 跨 8 个 endpoint（GET /devices）字节级一致
- DELETE recordId 不存在 / 跨账号 → 字节级一致 404 — 反枚举不变性
