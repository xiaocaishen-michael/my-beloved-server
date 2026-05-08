# Implementation Tasks: Device Management

**Spec**: [`./spec.md`](./spec.md) · **Plan**: [`./plan.md`](./plan.md) · **Analysis**: [`./analysis.md`](./analysis.md)
**Phase**: M1.X（账号中心 - 登录管理）
**Estimated total**: ~12-16h（V11 schema + 5 字段 + 5 既有 UseCase wiring + 2 新 UseCase + 1 controller + 6 测试类 + ip2region 引入）

> **TDD 节奏**：每条 task 严格红绿循环；测试任务绑定到实现 task。任务标签：`[Domain]` / `[App]` / `[Infra]` / `[Web]` / `[E2E]` / `[Concurrency]` / `[Wiring]` / `[Contract]`。
>
> **前置依赖**：spec 三件套 docs PR 已 ship；本 tasks 在新 impl PR 内执行。无其他 PR 前置（V5 refresh_token + JwtAuthFilter + RateLimitService + AccountSmsCodeRepository 等已就位）。
>
> **完成标记**：每 task 实施完成在标题紧跟 task 编号后加 `✅`（per meta CLAUDE.md sdd.md § /implement 闭环纪律）。

## Critical Path（按依赖顺序）

### T0 ✅ [Domain] DeviceType + LoginMethod enum + DeviceId / DeviceName / IpAddress 值对象

**Files**:

- `mbw-account/src/main/java/com/mbw/account/api/dto/DeviceType.java`（**新建** enum）
- `mbw-account/src/main/java/com/mbw/account/api/dto/LoginMethod.java`（**新建** enum）
- `mbw-account/src/main/java/com/mbw/account/domain/model/DeviceId.java`（**新建** record）
- `mbw-account/src/main/java/com/mbw/account/domain/model/DeviceName.java`（**新建** record）
- `mbw-account/src/main/java/com/mbw/account/domain/model/IpAddress.java`（**新建** record）

**Logic**:

- `DeviceType` enum：`PHONE / TABLET / DESKTOP / WEB / UNKNOWN`
- `LoginMethod` enum：`PHONE_SMS / GOOGLE / APPLE / WECHAT`（**不**含 `REFRESH` — per FR-012 rotation 继承旧值）
- `DeviceId(String value)` record + 构造器内 UUID v4 pattern 校验 + 静态工厂 `fromHeaderOrFallback(String header)`：缺 / 格式错 → `UUID.randomUUID().toString()` fallback（per CL-001 (a)）
- `DeviceName(String value)` record + max 64 char + trim 非空校验 + 静态工厂 `ofNullable(String raw)`：null/blank → null
- `IpAddress(String value)` record + IPv4/IPv6 pattern 校验 + `isPrivate()`（10.x / 172.16-31.x / 192.168.x / 127.x / IPv6 ::1 / fc00::/7）+ 静态工厂 `ofNullable(String raw)`

**Tests**:

- `DeviceTypeTest` / `LoginMethodTest` — enum 值断言（per CLAUDE.md § 一 record/enum 例外，仅断言 values 列表）
- `DeviceIdTest`：`should_accept_valid_uuid_v4()` / `should_reject_malformed()` / `should_fallback_to_random_uuid_when_header_null()` / `should_fallback_when_header_blank()` / `should_fallback_when_header_invalid()`
- `DeviceNameTest`：`should_trim_and_accept()` / `should_reject_when_over_64_chars()` / `should_return_null_when_input_blank()` / `should_return_null_when_input_null()`
- `IpAddressTest`：`should_accept_ipv4_public()` / `should_accept_ipv6_public()` / `should_detect_private_10x()` / `should_detect_loopback()` / `should_detect_ipv6_link_local()` / `should_reject_malformed()` / `should_return_null_when_input_blank()`

**Dependencies**: 无。可与 T1 / T2 并行。

---

### T1 ✅ [Domain] `RefreshTokenRecord` 扩展 5 字段

**File**: `mbw-account/src/main/java/com/mbw/account/domain/model/RefreshTokenRecord.java`（**改**）

**Logic**: 加 5 字段（deviceId / deviceName / deviceType / ipAddress / loginMethod）+ 工厂签名扩展（per `plan.md § Domain Model 字段`）；既有 `revoke(Instant)` / `isActive(Instant)` 行为不变。

**Tests**: `RefreshTokenRecordTest` 扩展（既有）：

- `should_construct_active_record_with_5_device_fields()` — happy
- `should_throw_when_deviceId_null()` — required
- `should_allow_null_deviceName_and_ipAddress()` — nullable wrapper
- `should_preserve_5_fields_after_revoke()` — revoke 不触动 device 元数据
- `should_reconstitute_with_all_fields()` — repository load path

**Dependencies**: T0。

---

### T2 ✅ [Domain] `Ip2RegionService` interface

**File**: `mbw-account/src/main/java/com/mbw/account/domain/service/Ip2RegionService.java`（**新建** interface）

**Logic**: `Optional<String> resolve(IpAddress ip)` — 返中文省市或空（私网 / null / 解析失败）

**Tests**: 无（接口本身无行为；adapter T8 测）。

**Dependencies**: T0。可与 T1 并行。

---

### T3 ✅ [Domain] `JwtTokenIssuer.signAccess(AccountId, DeviceId)` + did claim

**File**: `mbw-account/src/main/java/com/mbw/account/domain/service/JwtTokenIssuer.java`（**改**）

**Logic**:

- 新增 `signAccess(AccountId accountId, DeviceId deviceId, Instant now): String` — 在 JWT payload 加 `did=deviceId.value()` claim
- 删除 `signAccess(AccountId accountId, Instant now)`（不允许并存防 wiring 漏）
- `issueAccessAndRefresh(AccountId, DeviceId, Instant): TokenPair` — `signAccess` + `signRefresh` 入参也加 deviceId

**Tests**: `JwtTokenIssuerTest` 扩展：

- `should_include_did_claim_in_access_token()` — 解 JWT 断言 `did` claim 存在 + 等于入参 deviceId
- `should_throw_when_deviceId_null()` — required
- `should_keep_existing_claims_unchanged()` — sub / iat / exp / iss 不变

**Dependencies**: T0。

---

### T4 ✅ [Migration] V11 add 5 columns + index

**File**: `mbw-account/src/main/resources/db/migration/account/V11__extend_refresh_token_device_metadata.sql`（**新建**）

**Logic**: per `plan.md § V11 migration`。

**Tests**: 既有 `FlywayMigrationIT`（若有）覆盖；否则 T6 IT 间接覆盖（schema 不对齐启动即失败）。

**Dependencies**: 无。可与 T0/T1/T2/T3 并行。

---

### T5 ✅ [Infra] `RefreshTokenJpaEntity` + `RefreshTokenMapper` + `RefreshTokenJpaRepository` 扩展

**Files**:

- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/RefreshTokenJpaEntity.java`（**改** — 加 5 列 JPA 注解 + getter/setter）
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/RefreshTokenMapper.java`（**改** — 5 字段双向映射 + nullable wrapper 处理）
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/RefreshTokenJpaRepository.java`（**改** — 加 `findByAccountIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long accountId, Pageable)` 方法签名）
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/RefreshTokenRepositoryImpl.java`（**改** — 实现 `findActiveByAccountId(AccountId, Pageable)` 适配 domain 接口）

**Logic**:

- JpaEntity 5 列 `@Column` 注解（含 `nullable=false` for device_type / login_method，default 由 DB 处理）
- JpaRepository 走 partial index `idx_refresh_token_account_device_active`（V11 落地）
- Mapper 处理：DB device_id null → DeviceId.fromHeaderOrFallback 不适用（这是读路径，db 原值直接 wrap），但 db device_id 为 NULL 时 → throw（V11 之后写入路径必有值；migrating 中 NULL 视为 corrupt 数据）— 实际 fallback 在写入路径 T7 / T9 / T10 做

**Tests**: `RefreshTokenRepositoryImplIT` 扩展：

- `should_persist_and_load_5_device_fields()` — full round-trip
- `should_handle_null_deviceName_and_ipAddress()` — nullable
- `should_findActiveByAccountId_returns_paginated_active_only()` — partial index 走查 + sort by createdAt DESC
- `should_findActiveByAccountId_excludes_revoked()` — index covers active only

**Dependencies**: T0 + T1 + T4。

---

### T6 ✅ [Domain] `RefreshTokenRepository.findActiveByAccountId(AccountId, int, int)` 接口扩展

**File**: `mbw-account/src/main/java/com/mbw/account/domain/repository/RefreshTokenRepository.java`（**改**）

**Logic**: 加 `Page<RefreshTokenRecord> findActiveByAccountId(AccountId accountId, Pageable pageable)` 方法。

**Tests**: 接口扩展，无 unit 测；T5 IT 已覆盖。

**Dependencies**: T1。可与 T5 并行（接口先定，IT 实现走 T5）。

---

### T7 ✅ [Web] `DeviceMetadataExtractor` utility

**File**: `mbw-account/src/main/java/com/mbw/account/web/resolver/DeviceMetadataExtractor.java`（**新建** utility）

**Logic**:

- `extractIp(HttpServletRequest req): String|null`：取 `X-Forwarded-For` 最左非内网段；fallback `req.getRemoteAddr()`；私网 → null
- `extractDeviceMetadata(HttpServletRequest req): DeviceMetadata`（record `(DeviceId, DeviceName, DeviceType)`)：
  - `X-Device-Id` → `DeviceId.fromHeaderOrFallback(...)`
  - `X-Device-Name` → `DeviceName.ofNullable(...)`
  - `X-Device-Type` → `DeviceType.valueOf(...)`，invalid / null → `DeviceType.UNKNOWN`

**Tests**: `DeviceMetadataExtractorTest`（**新建**，纯 utility 单测）：

- `should_extract_ipv4_from_xff_first_segment()` / `should_skip_private_ips_in_xff()` / `should_fallback_to_remoteAddr_when_xff_empty()` / `should_return_null_when_all_private()`
- `should_fallback_uuid_when_xDeviceId_missing()` / `should_use_xDeviceId_when_provided()`
- `should_default_unknown_when_xDeviceType_invalid()` / `should_handle_null_xDeviceName()`

**Dependencies**: T0。可与 T1-T6 并行。

---

### T8 [Infra] `Ip2RegionAdapter` + .xdb 资源

**Files**:

- `mbw-account/pom.xml`（**改** — 加 `net.dreamlu:mica-ip2region:<version>` 依赖；版本 plan 阶段 lock）
- `mbw-account/src/main/java/com/mbw/account/infrastructure/geoip/Ip2RegionAdapter.java`（**新建**）
- `mbw-account/src/main/resources/geoip/ip2region.xdb`（**新建**，binary，~5MB，从 [ip2region GitHub Releases](https://github.com/lionsoul2014/ip2region/releases) 拉最新 xdb v3.0+）

**Logic**:

- `@Component` Ip2RegionAdapter 实现 `Ip2RegionService`：
  - `@PostConstruct` 加载 `classpath:geoip/ip2region.xdb` 到内存（vector index 预热）
  - `resolve(IpAddress ip)`：null 或 `isPrivate()` → empty；解析返 "中国|0|<省>|<市>|<运营商>" 切 split 取 `<省><市>`（如"上海上海" → "上海"，"广东深圳" → "深圳"）
  - 解析失败 → empty + log WARN

**Tests**: `Ip2RegionAdapterIT`（**新建**，需要真实 .xdb 文件）：

- `should_resolve_beijing_ip()` / `should_resolve_shanghai_ip()` / `should_resolve_guangdong_shenzhen_ip()` / `should_resolve_zhejiang_hangzhou_ip()` — 5 个固定 IP 命中
- `should_return_empty_for_private_10x_ip()` / `should_return_empty_for_loopback()` / `should_return_empty_for_null_input()` — 兜底

**Dependencies**: T0 + T2。可与 T5 并行。

---

### T9 [Wiring] 5 既有 token-issuing UseCase 接入新签名

**Files**（5 处 UseCase + 各自 Command）：

- `RegisterByPhoneUseCase` + `RegisterByPhoneCommand`
- `PhoneSmsAuthUseCase` + `PhoneSmsAuthCommand`
- `LoginByPasswordUseCase` + `LoginByPasswordCommand`（若仍存在）
- `RefreshTokenUseCase` + `RefreshTokenCommand`（特殊：rotation 继承元数据）
- `CancelDeletionUseCase` + `CancelDeletionCommand`

**Logic**:

- 每个 Command 增字段：`deviceId / deviceName / deviceType / ipAddress`（rotation 不接受 — 走 T9-refresh 特殊路径）
- 每个 UseCase：
  - 调 `JwtTokenIssuer.issueAccessAndRefresh(accountId, deviceId, now)`
  - `RefreshTokenRecord.createActive(...)` 入参增 5 元数据
- **`RefreshTokenUseCase` 特殊**（FR-012）：
  - findByTokenHash 取旧 row → 新 row deviceId / deviceName / deviceType / loginMethod **继承**旧 row；ipAddress 用本次请求的 IP（新值）
  - 调 `JwtTokenIssuer.signAccess(accountId, oldRow.deviceId, now)` — did 继承

**Tests**:

- 各 UseCase 既有 `*Test` 扩展：
  - 增加 5 个 mock 元数据入参断言：`RefreshTokenRecord.createActive(...)` 调用入参含正确的 deviceId / deviceName / deviceType / ipAddress / loginMethod
  - `JwtTokenIssuer.signAccess(accountId, deviceId, now)` 调用入参含正确的 deviceId
- `RefreshTokenUseCaseTest` 增 `should_inherit_device_metadata_from_old_row_in_rotation()` 关键 case
- `RefreshTokenUseCaseTest` 增 `should_use_new_ip_in_rotation_not_inherited()` — IP 是新值不继承

**Dependencies**: T0 + T1 + T3。

---

### T10 [App] `ListDevicesQuery` + `RevokeDeviceCommand` + `DeviceListResult` + `DeviceItem`

**Files**: 4 records under `mbw-account/src/main/java/com/mbw/account/application/{command,query,result}/`（**新建**）

**Logic**: 纯 record / DTO，按 `plan.md § Application layer` 列出字段。

**Tests**: 纯 record，TDD 例外。

**Dependencies**: T0。可与 T1-T9 并行。

---

### T11 [App] `ListDevicesUseCase`

**File**: `mbw-account/src/main/java/com/mbw/account/application/usecase/ListDevicesUseCase.java`（**新建**）

**Logic**: per `plan.md § Endpoint 1 流程`。

**Tests**: `ListDevicesUseCaseTest`（**新建**），Mockito mock 4 依赖（RateLimitService / RefreshTokenRepository / Ip2RegionService / Clock）。

**8 分支覆盖**:

- `should_return_paginated_items_with_isCurrent_flag()` — happy 3 device,1 isCurrent
- `should_clamp_size_to_100_when_request_size_over_100()` — FR-013 size cap
- `should_apply_default_size_10_when_unspecified()` — controller 层 default,UseCase 层接受任意 int
- `should_resolve_location_via_ip2region_for_each_item()` — 调 ip2region.resolve(item.ipAddress)
- `should_set_location_null_when_ip2region_returns_empty()` — null IP / private / fail → null
- `should_throw_RateLimitedException_when_account_throttled()`
- `should_throw_RateLimitedException_when_ip_throttled()`
- `should_return_empty_items_when_no_active_records()` — totalElements=0,items=[]

**Dependencies**: T1 + T2 + T6 + T10。

---

### T12 [App] `RevokeDeviceUseCase`

**File**: `mbw-account/src/main/java/com/mbw/account/application/usecase/RevokeDeviceUseCase.java`（**新建** + 2 exception class）

**Logic**: per `plan.md § Endpoint 2 流程`，`@Transactional(rollbackFor = Throwable.class)`。

新建 exception:

- `DeviceNotFoundException` extends RuntimeException — 404 mapping
- `CannotRemoveCurrentDeviceException` extends RuntimeException — 409 mapping

**Tests**: `RevokeDeviceUseCaseTest`（**新建**），Mockito mock 4 依赖（RateLimitService / RefreshTokenRepository / ApplicationEventPublisher / Clock）。

**10 分支覆盖**:

- `should_revoke_and_publish_event_when_other_device_valid()` — happy 非本机
- `should_throw_DeviceNotFound_when_recordId_does_not_exist()` — 404
- `should_throw_DeviceNotFound_when_recordId_belongs_to_other_account()` — 反枚举 404
- `should_throw_CannotRemoveCurrentDevice_when_record_deviceId_equals_current()` — 409
- `should_be_idempotent_200_when_record_already_revoked()` — 已 revoked 直接返
- `should_throw_RateLimited_when_account_throttled()`
- `should_throw_RateLimited_when_ip_throttled()`
- `should_rollback_when_save_throws()` — outbox / DB 失败回滚
- `should_rollback_when_publishEvent_throws()` — outbox 异常
- `should_log_INFO_with_short_deviceId_no_token_hash()` — log assertion

**Dependencies**: T1 + T6 + T10 + DeviceRevokedEvent (T13)。

---

### T13 ✅ [Domain] `DeviceRevokedEvent`

**File**: `mbw-account/src/main/java/com/mbw/account/api/event/DeviceRevokedEvent.java`（**新建**）

**Logic**: record `(AccountId accountId, RefreshTokenRecordId recordId, DeviceId deviceId, Instant revokedAt, Instant occurredAt)`，放 `api.event` 包。

**Tests**: 简单 record 断言（同 cancel-deletion T1 pattern）。

**Dependencies**: T0。可与 T1-T12 并行。

---

### T14 [Web] `DeviceManagementController` + `DeviceListResponse` + `DeviceItemResponse`

**Files**:

- `mbw-account/src/main/java/com/mbw/account/web/controller/DeviceManagementController.java`（**新建**）
- `mbw-account/src/main/java/com/mbw/account/web/response/DeviceListResponse.java`（**新建**）
- `mbw-account/src/main/java/com/mbw/account/web/response/DeviceItemResponse.java`（**新建**）
- `mbw-account/src/main/java/com/mbw/account/web/exception/GlobalExceptionHandler.java`（**改** — 加 2 个 mapping）

**Logic**: per `plan.md § Web layer`。`DeviceItemResponse` 不含 `ipAddress` 字段（per CL-002）。

**Tests**: `DeviceManagementControllerTest`（**新建**），`@WebMvcTest(DeviceManagementController.class)`：

- `should_return_200_with_paginated_response_when_authorized()` — list happy
- `should_return_400_when_size_negative()` / `should_return_400_when_page_negative()` — query param 校验
- `should_return_401_when_no_authorization_header()` — Spring Security 拦截
- `should_return_401_when_did_claim_missing()` — JwtAuthFilter 拦截 (FR-006)
- `should_return_429_when_use_case_throws_RateLimited()` — list endpoint
- `should_return_204_when_revoke_succeeds()` — DELETE happy（**改**：should_return_200，per FR-003 200 No Content）
- `should_return_404_when_DeviceNotFoundException_thrown()` — DELETE 404
- `should_return_409_when_CannotRemoveCurrentDeviceException_thrown()` — DELETE 409
- `should_return_429_when_revoke_use_case_throws_RateLimited()` — DELETE
- `should_omit_ipAddress_from_response_per_CL_002()` — response schema 检查无 ipAddress 字段

**Dependencies**: T11 + T12。

---

### T15 [E2E] `DeviceManagementControllerE2EIT`

**File**: `mbw-account/src/test/java/com/mbw/account/web/DeviceManagementControllerE2EIT.java`（**新建**）

**Logic**: Testcontainers PG + Redis + 真 ip2region 加载。覆盖 spec.md 5 个 User Stories 全部 acceptance + Edge Cases + SC。

**Test cases**（按 User Story）：

- US1（5 AS）：3 device list / location 解析 / 1 self only / 12 paginate / 翻页本机标记可见性
- US2（5 AS）：移除非本机 happy / refresh-token 401 后续 / list refetch 不显示 / 旧 access token TTL 内仍可用 / 跨账号 recordId 404
- US3（3 AS）：当前 device 409 / 缺 did 401 / did 找不到 row 仍 200 移除非本机
- US4（3 AS）：无 auth 401 / 过期 token 401 / FROZEN 账号 403
- US5（3 AS）：list 限流 / DELETE 限流 / IP 维度限流

**Fixture**: BeforeEach 起空 PG schema + 预设账号 + 3-5 active refresh_token rows（不同 device_id / device_name / device_type / login_method / ip_address）+ ip2region 真加载。

**Dependencies**: T0-T14 完成。

---

### T16 [Concurrency] `DeviceManagementConcurrencyIT`

**File**: `mbw-account/src/test/java/com/mbw/account/web/DeviceManagementConcurrencyIT.java`（**新建**）

**Logic**: per spec.md SC-003 atomicity + Edge Cases。

**Test cases**:

- `should_only_one_succeed_when_same_device_revoked_5_times_concurrently()` — 5 线程并发 DELETE 同一 recordId → 1 个 200 + 4 个幂等 200（per FR-003）；DB row 单次 revoke
- `should_keep_state_unchanged_when_outbox_publish_fails()` — Mockito spy 让 publishEvent 抛异常 → 断言 row.revoked_at 仍 null
- `should_allow_concurrent_rotation_during_list_query()` — 1 线程跑 GET /devices；同时另一线程跑 refresh-token rotation → list 返结果一致（无 deadlock）

**Dependencies**: T0-T14 完成。可与 T15 并行。

---

### T17 [Wiring] Cross-spec regression — `DeviceMetadataPropagationIT`

**File**: `mbw-account/src/test/java/com/mbw/account/web/DeviceMetadataPropagationIT.java`（**新建**）

**Logic**: 验 5 既有 token-issuing UseCase 接入 device 元数据后行为正确（spec SC-006 / SC-013）。

**Test cases**:

- `should_persist_5_device_fields_on_phone_sms_auth_login()` — 调 phone-sms-auth login → DB row 含 5 字段
- `should_persist_5_device_fields_on_register_by_phone()` — 同上 register
- `should_inherit_4_fields_and_use_new_ip_on_refresh_rotation()` — refresh-token rotation 验继承 + 新 IP（FR-012）
- `should_persist_5_device_fields_on_cancel_deletion()` — cancel-deletion 转 ACTIVE 后新 row
- `should_keep_phone_sms_auth_response_byte_compatible_with_spec()` — 现有 response schema 不变（FR-016）
- `should_keep_logout_all_behavior_unchanged()` — logout-all 不签 token，行为不变
- `should_keep_existing_login_method_unchanged_when_no_xDeviceMethod_header_passed()` — 既有 IT 不感知 device header 仍可正常运行（fallback 路径）

**Dependencies**: T9 完成。可与 T15 / T16 并行。

---

### T18 [Contract] OpenAPI snapshot regen + `did` claim doc

**File**: `mbw-account/src/test/resources/api-docs.snapshot.json`（**改**）

**Logic**: 既有 `OpenApiSnapshotIT` 自动覆盖；新 endpoint 进 schema；现有 endpoint 不动；`securitySchemes` Bearer JWT description 加 `did` claim 说明（手动 `@OpenAPIDefinition` 修订）。

**Tests**: `OpenApiSnapshotIT` 既有覆盖。

**Dependencies**: T14 完成。可与 T15-T17 并行。

---

## Parallel Opportunities

- **T0 / T2 / T4 / T7 / T13 同起**（无相互依赖）
- **T1 / T3 在 T0 完成后**
- **T5 在 T0 + T1 + T4 完成后**
- **T6 在 T1 完成后**
- **T8 在 T0 + T2 完成后**
- **T9 在 T0 + T1 + T3 完成后**（需新签名 + Record 工厂）
- **T10 / T11 / T12 在 T6 + T13 完成后**（依赖 Repository 接口 + Event）
- **T14 在 T11 + T12 完成后**
- **T15 / T16 / T17 / T18 在 T0-T14 完成后**（4 个测试 task 全并行）

## Definition of Done

- ✅ 18 任务的 unit + IT 测试 RED → GREEN
- ✅ `./mvnw -pl mbw-account verify` 全绿
- ✅ `./mvnw -pl mbw-app -Dtest=ModuleStructureTest test` 全绿（ArchUnit + Spring Modulith Verifier）
- ✅ Spotless + Checkstyle 全绿
- ✅ OpenAPI snapshot 含 GET /devices + DELETE /devices/{id}
- ✅ Cross-spec regression 测试 GREEN（T17）— 5 既有 UseCase 行为不变
- ✅ Concurrency 测试 GREEN（T16）— 并发 revoke 幂等 + outbox 失败回滚
- ✅ outbox 写入测试 GREEN（DeviceRevokedEvent 持久化）
- ✅ ip2region 5 IP 测试 GREEN（T8）
- ✅ V11 migration 落地 + Flyway baseline 不破

## Phasing PR 拆分

按 SDD § 双阶段切分：

- **PR 1（前 3 PR docs-only 已 ship）**: spec / mockup-prompt / mockup bundle + handoff
- **PR 2（本 plan/tasks docs PR）**: `docs(account): device-management plan + tasks + analysis`
- **PR 3（impl，本 spec 范围外）**: `feat(account): impl device-management (M1.X / T0-T18)`
  - 前置：plan/tasks PR 已 merge
  - 后续触发：app 仓 OpenAPI 重生 + impl PR
