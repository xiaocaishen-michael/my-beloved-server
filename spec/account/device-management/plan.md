# Implementation Plan: Device Management

**Spec**: [`./spec.md`](./spec.md) · **Tasks**: [`./tasks.md`](./tasks.md) · **Analysis**: [`./analysis.md`](./analysis.md)
**Phase**: M1.X（账号中心 - 登录管理 / 设备 session 列表 + 单设备移除）
**Created**: 2026-05-08

> 本 plan 在 [`../../auth/refresh-token/plan.md`](../../auth/refresh-token/plan.md) 既有 `RefreshTokenRecord` + V5 migration + rotation-on-each-use 之上**扩展 5 列 + 新 use case 对**。所有既有 token-issuing UseCase（phone-sms-auth / register-by-phone / refresh-token / cancel-deletion）必须**接入新 `signAccess(AccountId, DeviceId)` 签名**——本 plan 显式列出每条 wiring。
>
> **per [my-beloved-server CLAUDE.md § expand-migrate-contract](https://github.com/xiaocaishen-michael/my-beloved-server/blob/main/CLAUDE.md#不兼容变更expand-migrate-contract-三步法)**：M3 内测前允许 V11 单 PR 落 5 列扩展（条件 1 满足 = 无真实用户数据，条件 2 显式声明）。

## 架构层级与职责（DDD 五层，仅列改动）

```text
mbw-account/
├── api/
│   ├── dto/
│   │   ├── DeviceType.java                          — 新建 enum (FR-007)
│   │   └── LoginMethod.java                         — 新建 enum (FR-007)
│   └── event/
│       └── DeviceRevokedEvent.java                  — 新建 (FR-017)
│
├── domain/
│   ├── model/
│   │   ├── DeviceId.java                            — 新建 record value object
│   │   ├── DeviceName.java                          — 新建 record value object
│   │   ├── IpAddress.java                           — 新建 record value object（含 isPrivate()）
│   │   └── RefreshTokenRecord.java                  — 改（加 5 字段 + factory + reconstitute 签名扩展）
│   ├── repository/
│   │   └── RefreshTokenRepository.java              — 改（加 findActiveByAccountId(AccountId, Pageable)）
│   └── service/
│       ├── JwtTokenIssuer.java                      — 改（signAccess(AccountId, DeviceId) 重构 + did claim）
│       └── Ip2RegionService.java                    — 新建 domain interface
│
├── application/
│   ├── command/
│   │   ├── ListDevicesQuery.java                    — 新建
│   │   └── RevokeDeviceCommand.java                 — 新建
│   ├── result/
│   │   ├── DeviceListResult.java                    — 新建
│   │   └── DeviceItem.java                          — 新建
│   └── usecase/
│       ├── ListDevicesUseCase.java                  — 新建
│       └── RevokeDeviceUseCase.java                 — 新建
│
├── infrastructure/
│   ├── geoip/
│   │   └── Ip2RegionAdapter.java                    — 新建（mica-ip2region 适配器 + .xdb 资源加载）
│   └── persistence/
│       ├── RefreshTokenJpaEntity.java               — 改（加 5 列映射）
│       ├── RefreshTokenJpaRepository.java           — 改（findActiveByAccountIdOrderByCreatedAtDesc(AccountId, Pageable)）
│       └── RefreshTokenMapper.java                  — 改（5 字段双向映射）
│
└── web/
    ├── controller/
    │   └── DeviceManagementController.java          — 新建
    ├── request/
    │   └── （DELETE / GET 无 body，仅 path / query 参数）
    ├── response/
    │   ├── DeviceListResponse.java                  — 新建
    │   └── DeviceItemResponse.java                  — 新建
    ├── resolver/
    │   └── DeviceMetadataExtractor.java             — 新建（X-Device-* + X-Forwarded-For 提取 + 私网过滤）
    └── exception/
        └── （沿用既有 GlobalExceptionHandler，新增 DeviceNotFoundException + CannotRemoveCurrentDeviceException → ProblemDetail 映射）
```

**不动**：`AccountJpaEntity` / `AccountRepository` / `AccountSmsCodeRepository` / `RateLimitService` 接口。

## 既有 5 个 token-issuing 路径接入（FR-008/FR-012）

> ⚠ 本节是本 spec 最大 wiring 范围。每个既有 UseCase 都需更新签名。

| UseCase | 入参 Command 增加字段 | 调用变更 |
|---|---|---|
| `RegisterByPhoneUseCase` | `+ DeviceMetadata{deviceId, deviceName, deviceType, loginMethod=PHONE_SMS}` | `JwtTokenIssuer.signAccess(accountId, deviceId)` + `RefreshTokenRecord.createActive(...)` 入参增 5 元数据 |
| `PhoneSmsAuthUseCase` | 同上 | 同上 |
| `LoginByPasswordUseCase`（若仍存在）| 同上但 `loginMethod=PHONE_SMS` 复用 / 视既有路径而定 | 同上 |
| `RefreshTokenUseCase` | `+ DeviceMetadata{ipAddress=新请求的 IP}`；不接受新 deviceId / deviceName / deviceType / loginMethod（per FR-012：rotation **继承**旧 row 的元数据，仅 IP 用新值）| `findByTokenHash` 取旧 row → `RefreshTokenRecord.createActive(...)` 入参 = (旧 row.deviceId, 旧 row.deviceName, 旧 row.deviceType, 新 IP, 旧 row.loginMethod) |
| `CancelDeletionUseCase` | `+ DeviceMetadata{deviceId, deviceName, deviceType, loginMethod=PHONE_SMS}` | 同 phone-sms-auth |

**绕开本 spec 的 UseCase**（不签 access token）：

- `LogoutAllUseCase` — 仅 revoke，不签 token
- `SendCancelDeletionCodeUseCase` / `SendDeleteAccountCodeUseCase` / `RequestSmsCodeUseCase` — 仅发码

## 核心 use case 流程

### Endpoint 1: `GET /api/v1/auth/devices?page&size`

**目的**：列当前账号所有 active refresh_token 折算的设备。

**入参**：query `page>=0` / `size 1..100 default 10`；header `Authorization: Bearer <accessToken>`（必含 `did` claim per FR-006）。

**响应**：200 + `DeviceListResponse { page, size, totalElements, totalPages, items: DeviceItemResponse[] }` / 401 / 403 / 429。

**流程**：

1. Web — `DeviceManagementController.list(@RequestParam page, size, Authentication auth)`：
   - JwtAuthFilter 已校验 access token；从 `auth.getPrincipal()` 取 `accountId` + `did claim` → 缺 did → 401 INVALID_CREDENTIALS（per FR-006）
   - 调 `ListDevicesUseCase.execute(new ListDevicesQuery(accountId, currentDeviceId, page, size))`
2. Application — `ListDevicesUseCase`：
   1. `RateLimitService.tryAcquire("device-list:account:<id>", 60s, 30)` + ip 维度 60s 100
   2. `clampedSize = min(size, 100)` per FR-013
   3. `Page<RefreshTokenRecord> page = refreshTokenRepository.findActiveByAccountId(accountId, PageRequest.of(page, clampedSize, Sort.by("createdAt").descending()))`
   4. map：每个 record → `DeviceItem { id, deviceId, deviceName, deviceType, ipAddress=null /* per CL-002 不暴 */, location=ip2region.resolve(record.ipAddress).orElse(null), loginMethod, lastActiveAt=record.createdAt, isCurrent = (record.deviceId.equals(currentDeviceId)) }`
   5. 返 `DeviceListResult(page, clampedSize, totalElements, totalPages, items)`
3. Web — controller map → `DeviceListResponse`，`ResponseEntity.ok(...)`

### Endpoint 2: `DELETE /api/v1/auth/devices/{recordId}`

**目的**：revoke 指定设备的 refresh_token row（强制拒删当前 device）。

**入参**：path `recordId: long`；header `Authorization`。

**响应**：200 No Content / 401 / 403 / 404 DEVICE_NOT_FOUND / 409 CANNOT_REMOVE_CURRENT_DEVICE / 429。

**流程**：

1. Web — `DeviceManagementController.revoke(@PathVariable recordId, Authentication auth)`
2. Application — `RevokeDeviceUseCase` (`@Transactional(rollbackFor = Throwable.class)`)：
   1. 限流 `device-revoke:account:<id>` 60s 5 + ip 60s 20
   2. `var record = refreshTokenRepository.findById(RefreshTokenRecordId.of(recordId)).orElseThrow(DeviceNotFoundException::new)` → 404
   3. `if (!record.accountId().equals(accountId)) throw new DeviceNotFoundException()` — 反枚举：跨账号视为不存在
   4. `if (record.deviceId().equals(currentDeviceId)) throw new CannotRemoveCurrentDeviceException()` — per FR-005
   5. `if (record.revokedAt() != null) return; /* 幂等：已 revoked 直接 200 */`
   6. `refreshTokenRepository.save(record.revoke(now))`
   7. `eventPublisher.publishEvent(new DeviceRevokedEvent(accountId, recordId, deviceId, now, now))`
   8. log INFO `device.revoked accountId=<id> recordId=<id> deviceId=<short>`（不打 token_hash）
3. Web — `ResponseEntity.ok().build()`

## 数据流（请求生命周期）

```text
Client (authenticated)
  │  Authorization: Bearer <accessToken with did claim>
  │  GET /api/v1/auth/devices?page=0&size=10
  ▼
JwtAuthFilter (验签 + 解 sub + did claim → Authentication)
  │  缺 did claim → 401 INVALID_CREDENTIALS (per FR-006)
  ▼
DeviceManagementController.list(page, size, auth)
  ▼
ListDevicesUseCase.execute(query)
  │  - 限流 (account 30/min + ip 100/min)
  │  - clampedSize = min(size, 100)
  │  - findActiveByAccountId(accountId, Pageable)
  │  - 标 isCurrent (record.deviceId == auth.did)
  │  - ip2region.resolve(record.ipAddress) → location
  ▼
200 OK + DeviceListResponse

------ revoke 流程 ------

Client
  │  DELETE /api/v1/auth/devices/{recordId}
  ▼
JwtAuthFilter (同上)
  ▼
DeviceManagementController.revoke(recordId, auth)
  ▼
RevokeDeviceUseCase.execute(cmd) [TRANSACTIONAL]
  │  - 限流 (account 5/min + ip 20/min)
  │  - findById → 404 if 不存在
  │  - 跨账号视为不存在 → 404 (反枚举)
  │  - record.deviceId == auth.did → 409 CANNOT_REMOVE_CURRENT (FR-005)
  │  - 幂等 (已 revoked → 200)
  │  - record.revoke(now) + save
  │  - publish DeviceRevokedEvent
  ▼
200 No Content
```

## 数据模型变更

### V11 migration

**File**: `mbw-account/src/main/resources/db/migration/account/V11__extend_refresh_token_device_metadata.sql`

```sql
-- Device-management spec: extend refresh_token with 5 device metadata columns.
-- Per CLAUDE.md § expand-migrate-contract: M3 internal-testing 前允许单 PR 落
-- expand+migrate+contract（条件 1=无生产用户 + 条件 2=本注释显式声明）。

ALTER TABLE account.refresh_token
    ADD COLUMN device_id     VARCHAR(36) NULL,                                  -- UUID v4 from client (X-Device-Id) or fallback gen_random_uuid()
    ADD COLUMN device_name   VARCHAR(64) NULL,                                  -- client-reported (expo-device.deviceName) or NULL
    ADD COLUMN device_type   VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',           -- enum: PHONE/TABLET/DESKTOP/WEB/UNKNOWN
    ADD COLUMN ip_address    VARCHAR(45) NULL,                                  -- IPv4 or IPv6 (max 45 chars), private/loopback filtered to NULL
    ADD COLUMN login_method  VARCHAR(16) NOT NULL DEFAULT 'PHONE_SMS';         -- enum: PHONE_SMS/GOOGLE/APPLE/WECHAT (REFRESH path inherits old row's value, per FR-012)

COMMENT ON COLUMN account.refresh_token.device_id   IS 'Stable client-side UUID v4, persisted in client expo-secure-store; fallback to server-generated UUID if header X-Device-Id missing (per spec CL-001 (a)).';
COMMENT ON COLUMN account.refresh_token.device_name IS 'Client-reported device name (e.g., "MK-iPhone"); NULL when client omits X-Device-Name header.';
COMMENT ON COLUMN account.refresh_token.device_type IS 'Coarse-grained type used for UI icon selection.';
COMMENT ON COLUMN account.refresh_token.ip_address  IS 'IP at row insert time (login or refresh). Private/loopback IPs filtered to NULL pre-persist.';
COMMENT ON COLUMN account.refresh_token.login_method IS 'Login mechanism used to issue this device session. REFRESH path inherits parent row''s value to preserve "first login method" semantics.';

-- list query走 (account_id, device_id) 联合 + active 过滤;sort 走 created_at DESC.
CREATE INDEX idx_refresh_token_account_device_active
    ON account.refresh_token (account_id, device_id)
    WHERE revoked_at IS NULL;
```

### Domain Model 字段

`RefreshTokenRecord` 加 5 字段 + 工厂签名：

```java
public final class RefreshTokenRecord {
    private final RefreshTokenRecordId id;
    private final RefreshTokenHash tokenHash;
    private final AccountId accountId;
    private final DeviceId deviceId;            // 新
    private final DeviceName deviceName;         // 新（nullable wrapper）
    private final DeviceType deviceType;         // 新
    private final IpAddress ipAddress;           // 新（nullable wrapper）
    private final LoginMethod loginMethod;       // 新
    private final Instant expiresAt;
    private final Instant revokedAt;
    private final Instant createdAt;

    public static RefreshTokenRecord createActive(
        RefreshTokenHash tokenHash, AccountId accountId,
        DeviceId deviceId, DeviceName deviceName, DeviceType deviceType,
        IpAddress ipAddress, LoginMethod loginMethod,
        Instant expiresAt, Instant now) { ... }

    public static RefreshTokenRecord reconstitute(
        RefreshTokenRecordId id, RefreshTokenHash tokenHash, AccountId accountId,
        DeviceId deviceId, DeviceName deviceName, DeviceType deviceType,
        IpAddress ipAddress, LoginMethod loginMethod,
        Instant expiresAt, Instant revokedAt, Instant createdAt) { ... }

    // 新 accessor: deviceId() / deviceName() / deviceType() / ipAddress() / loginMethod()
}
```

## 复用既有基础设施

| 资产 | 来源 | 用途 |
|---|---|---|
| `RefreshTokenRecord` 聚合根 | refresh-token spec | 扩 5 字段；revoke + isActive 行为不变 |
| `RefreshTokenRepository.findById` / `save` | refresh-token spec | revoke endpoint 用 |
| `JwtAuthFilter` / Spring Security | 既有 | 鉴权 + 解 sub/did claim |
| `JwtTokenIssuer.signAccess(AccountId)` | 既有 | **改造为 signAccess(AccountId, DeviceId) + did claim**；旧签名 deprecate 后删 |
| `RateLimitService` | 既有 | 4 限流键 |
| `ApplicationEventPublisher` + Spring Modulith outbox | 既有 | DeviceRevokedEvent |
| `GlobalExceptionHandler` | 既有 | 加 2 个新 exception → ProblemDetail 映射 |
| `LoginResponse` schema | phone-sms-auth | 不动 — device-management 不签 token |

## 新增组件

### Domain layer

- `DeviceId(String value)` record + `Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")` 校验 + `fromHeaderOrFallback(String header)` 静态工厂
- `DeviceName(String value)` record + max 64 char trim + `ofNullable(String)` 静态工厂
- `IpAddress(String value)` record + IPv4/IPv6 pattern + `isPrivate()` 判内网（10.x / 172.16-31.x / 192.168.x / 127.x / IPv6 ::1 / fc00::/7）
- `DeviceType` enum (`PHONE/TABLET/DESKTOP/WEB/UNKNOWN`) — 放 `mbw-account.api.dto`
- `LoginMethod` enum (`PHONE_SMS/GOOGLE/APPLE/WECHAT`) — 放 `mbw-account.api.dto`
- `Ip2RegionService` interface：`Optional<String> resolve(IpAddress ip)`
- `JwtTokenIssuer.signAccess(AccountId, DeviceId)` — 新签名；emit JWT 含 `sub=accountId / did=deviceId.value()`；旧 `signAccess(AccountId)` 标 `@Deprecated` 后**删**（不允许并存防 wiring 漏）
- `DeviceRevokedEvent` record `(AccountId accountId, RefreshTokenRecordId recordId, DeviceId deviceId, Instant revokedAt, Instant occurredAt)` — 放 `mbw-account.api.event`

### Application layer

- `ListDevicesQuery(AccountId accountId, DeviceId currentDeviceId, int page, int size, String clientIp)` record
- `RevokeDeviceCommand(AccountId accountId, RefreshTokenRecordId recordId, DeviceId currentDeviceId, String clientIp)` record
- `DeviceListResult(int page, int size, long totalElements, int totalPages, List<DeviceItem> items)`
- `DeviceItem(long id, String deviceId, String deviceName, DeviceType deviceType, String location, LoginMethod loginMethod, Instant lastActiveAt, boolean isCurrent)` — **不**含 ipAddress（per CL-002）
- `ListDevicesUseCase` — 见上文流程
- `RevokeDeviceUseCase` `@Transactional`

### Infrastructure layer

- `Ip2RegionAdapter` — wraps `net.dreamlu:mica-ip2region`；启动时加载 `ip2region.xdb`（资源 `src/main/resources/geoip/ip2region.xdb`，~5MB，[ip2region 项目](https://github.com/lionsoul2014/ip2region) 提供）；查询接口同步返回；线程安全
- `RefreshTokenJpaEntity` 加 5 列字段 + JPA 注解
- `RefreshTokenJpaRepository.findActiveByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable)` — 走 V11 idx_refresh_token_account_device_active 索引（partial index over revoked_at IS NULL）
- `RefreshTokenMapper` — 5 字段双向映射 + nullable wrapper 处理

### Web layer

- `DeviceMetadataExtractor` (utility class) — `extractIp(HttpServletRequest)` + `extractDeviceMetadata(HttpServletRequest)`：
  - IP：取 `X-Forwarded-For` 最左非内网段；fallback `request.getRemoteAddr()`；私网 → null
  - X-Device-Id / X-Device-Name / X-Device-Type → 三个 header；缺失走 `DeviceId.fromHeaderOrFallback`（gen UUID v4）/ DeviceName null / DeviceType UNKNOWN
- `DeviceManagementController`：

  ```java
  @RestController
  @RequestMapping("/api/v1/auth/devices")
  @Tag(name = "Device Management")
  public class DeviceManagementController {

      @GetMapping
      public ResponseEntity<DeviceListResponse> list(
              @RequestParam(defaultValue = "0") int page,
              @RequestParam(defaultValue = "10") int size,
              Authentication auth, HttpServletRequest req) {
          var accountId = ((JwtPrincipal) auth.getPrincipal()).accountId();
          var currentDeviceId = ((JwtPrincipal) auth.getPrincipal()).deviceId(); // 缺 did → JwtAuthFilter 已 401
          var clientIp = DeviceMetadataExtractor.extractIp(req);
          var result = listDevicesUseCase.execute(
              new ListDevicesQuery(accountId, currentDeviceId, page, size, clientIp));
          return ResponseEntity.ok(DeviceListResponse.from(result));
      }

      @DeleteMapping("/{recordId}")
      public ResponseEntity<Void> revoke(
              @PathVariable long recordId, Authentication auth, HttpServletRequest req) {
          var accountId = ((JwtPrincipal) auth.getPrincipal()).accountId();
          var currentDeviceId = ((JwtPrincipal) auth.getPrincipal()).deviceId();
          var clientIp = DeviceMetadataExtractor.extractIp(req);
          revokeDeviceUseCase.execute(
              new RevokeDeviceCommand(accountId, RefreshTokenRecordId.of(recordId), currentDeviceId, clientIp));
          return ResponseEntity.ok().build();
      }
  }
  ```

- `DeviceListResponse` / `DeviceItemResponse` — DTO，仅做 application layer DTO → web DTO 映射（剥离 ipAddress per CL-002）
- `GlobalExceptionHandler` 加 mapping：
  - `DeviceNotFoundException` → 404 ProblemDetail `code=DEVICE_NOT_FOUND`
  - `CannotRemoveCurrentDeviceException` → 409 ProblemDetail `code=CANNOT_REMOVE_CURRENT_DEVICE` + message "当前设备请通过『退出登录』移除"

## 反枚举设计（边界确认）

| 端点 | 反枚举对象 | 实现 |
|---|---|---|
| `DELETE /devices/{id}` | "recordId 是否存在 / 是否属本账号" | 不存在 + 跨账号 → 字节级一致 404 DEVICE_NOT_FOUND；时延差 ≤ 50ms（per spec SC-007） |

**已知小差异**：list 的 `isCurrent=true` 仅出现在 `currentDeviceId` 对应那条 — 攻击者拿到 access token 才能调 list，已是 authenticated 路径，无横向 enumeration 空间。

## 事件流

```text
RevokeDeviceUseCase
  │  (after row.revoked_at persisted)
  ▼
ApplicationEventPublisher.publishEvent(DeviceRevokedEvent)
  │  (Spring Modulith outbox 持久化,与事务同提交)
  ▼
[本期无 listener consume]
  │
  └── (M2+) 安全审计模块 / 通知模块 订阅
      → "您在 X 设备的登录已被移除" 推送通知
```

## 测试策略

| 层 | 测试类 | 覆盖 |
|---|---|---|
| Domain unit | `DeviceIdTest` / `DeviceNameTest` / `IpAddressTest`（新建 ×3）| value object 校验 + isPrivate + fromHeaderOrFallback |
| Domain unit | `DeviceTypeTest` / `LoginMethodTest` | enum 值断言 |
| Domain unit | `RefreshTokenRecordTest` 扩展 | 5 新字段 factory + reconstitute + 不变性 |
| Domain unit | `JwtTokenIssuerTest` 扩展 | did claim 写入 + 解 |
| App unit | `ListDevicesUseCaseTest` 新建 | 7 分支：happy / 限流 ×2 / clamp size / 空 list / 1 self / 12 paginate |
| App unit | `RevokeDeviceUseCaseTest` 新建 | 9 分支：happy 非本机 / 404 不存在 / 404 跨账号 / 409 本机 / 幂等 已revoked / 限流 ×2 / outbox 失败回滚 / save 失败回滚 |
| Infrastructure IT | `RefreshTokenRepositoryImplIT` 扩展 | findActiveByAccountId pagination + sort + 5 列读写 |
| Infrastructure IT | `Ip2RegionAdapterIT` 新建 | 5 IP（北上广深杭）→ 中文省市命中 + 私网 / loopback / null → empty |
| Web IT | `DeviceManagementControllerE2EIT` 新建 | 14+ acceptance scenarios per spec |
| Concurrency IT | `DeviceManagementConcurrencyIT` 新建 | 同 device 并发 revoke 5 次 → 1 成功 + 4 幂等 200 |
| Cross-spec IT | `DeviceMetadataPropagationIT` 新建 | refresh-token rotation 后新 row 继承元数据 + IP 用新值；phone-sms-auth / register-by-phone / cancel-deletion 全 IT 验 device 元数据落库 |
| Contract | `OpenApiSnapshotIT` 既有自动覆盖 | 含 GET / DELETE devices |

## API 契约变更（OpenAPI + 前端 client）

新 endpoint：

- `GET /api/v1/auth/devices` — Bearer-protected
- `DELETE /api/v1/auth/devices/{recordId}` — Bearer-protected

**OpenAPI snapshot 重生成**（既有 `OpenApiSnapshotIT` 自动覆盖）。前端 client `pnpm api:gen` 在 server PR ship 后跑 → 自动新方法 `getDeviceManagementApi().listDevices(page, size)` / `revokeDevice(recordId)`，由 app 仓单独 PR 接入。

## Constitution Check

- ✅ **Modular Monolith**：仅 mbw-account 内改动；DeviceType / LoginMethod / DeviceRevokedEvent 在 api 包跨模块可见
- ✅ **DDD 5-Layer**：严格分层（值对象 + Repository 接口 + Adapter 实现 + UseCase 编排 + Controller 接入）
- ✅ **TDD Strict**：每 task 红绿循环；record / enum / DTO 例外按 CLAUDE.md § 一明示
- ✅ **Repository pattern**：domain 接口 + JpaEntity + Mapper + RepositoryImpl 既有 4 件套扩展
- ✅ **No cross-schema FK**：device_id 无 FK 引用其他表
- ✅ **Flyway immutable + expand-migrate-contract 跳步**：V11 单 PR 落 5 列扩展，PR 描述显式声明跳步理由
- ✅ **OpenAPI 单一真相源**：spec.md 不重复 OpenAPI 字节
- ✅ **No password / token in logs**：log 仅 accountId + recordId + deviceId 短前缀
- ✅ **JDK 21 + Spring Boot 3.5.x + Spring Modulith 1.4.x** — 不变
- ✅ **No state regression**：现有 phone-sms-auth / register-by-phone / cancel-deletion / refresh-token IT 必须 GREEN（cross-spec regression 测试覆盖）

## 反模式（明确避免）

- ❌ 把 device_id / device_name 作为 Account 字段（一账号多设备语义不符）— 必须挂在 RefreshTokenRecord
- ❌ 用 token_hash 作 list 唯一标识对外暴露（隐私 + 反枚举攻击面）— 用 RefreshTokenRecord.id 即可
- ❌ refresh-token rotation 把 login_method 写成 `REFRESH`（破坏"该设备首次登录方式"语义）— FR-012 强制继承
- ❌ 把 isCurrent 判定让 client 做（client 不可信，DELETE 路径必须 server-side 校验）— FR-005 强制
- ❌ 旧 access token（无 did claim）走兼容路径（防 bypass FR-005 校验）— FR-006 强制 401
- ❌ list response 暴 raw IP（CL-002 决议仅暴 location）
- ❌ 跨账号 recordId 返 403 而非 404（暴露"存在但不属你"信号）— 反枚举字节级一致 404

## References

- [`./spec.md`](./spec.md)
- [`../../auth/refresh-token/plan.md`](../../auth/refresh-token/plan.md) — RefreshTokenRecord 既有结构 + rotation 语义
- [`../../auth/refresh-token/spec.md`](../../auth/refresh-token/spec.md) — token rotation FR-009
- [`../../auth/logout-all/plan.md`](../../auth/logout-all/plan.md) — Bearer-protected endpoint pattern
- [`../cancel-deletion/plan.md`](../cancel-deletion/plan.md) — token issuance + outbox event 同款 pattern
- [`../delete-account/plan.md`](../delete-account/plan.md) — Bearer + transactional + outbox 同款
- [PRD § 5.4 强制退出其他设备](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#54-强制退出其他设备)
- [meta `docs/architecture/modular-strategy.md`](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/architecture/modular-strategy.md)
- [my-beloved-server CLAUDE.md § expand-migrate-contract](https://github.com/xiaocaishen-michael/my-beloved-server/blob/main/CLAUDE.md#不兼容变更expand-migrate-contract-三步法)
- [ip2region GitHub](https://github.com/lionsoul2014/ip2region) — Java port + .xdb 资源
