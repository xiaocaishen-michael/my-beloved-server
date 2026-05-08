# Implementation Plan: Realname Verification

**Spec**: [`./spec.md`](./spec.md)
**Module**: `mbw-account`
**Phase**: M1.X（紧随 phone-sms-auth / delete / cancel-deletion 后；先于 mbw-billing）
**Status**: Draft（pending impl）

## 架构层级与职责（DDD 五层）

```text
web         RealnameController (#queryMe / #initiate / #queryByBizId)
              ↓ Request DTO (RealnameInitiateRequest)
application QueryRealnameStatusUseCase
            InitiateRealnameVerificationUseCase
            ConfirmRealnameVerificationUseCase
              ↓ (依赖注入，via application.port)
domain      RealnameProfile (聚合根)
            IdentityNumberValidator (GB 11643 校验)
            RealnameProfileRepository (纯接口)
            RealnameStateMachine (UNVERIFIED/PENDING/VERIFIED/FAILED 状态转换)
            CipherService (port，抽象密码学接口)
            RealnameVerificationProvider (port，抽象阿里云调用)
              ↑
infrastructure  RealnameProfileRepositoryImpl + RealnameProfileJpaRepository + Mapper
                EnvDekCipherService (M1)  / AliyunKmsCipherService (M3 placeholder)
                AliyunRealnameClient (真) / BypassRealnameClient (dev)
                RateLimitService (复用，新增 realname:* bucket)
                AccountAgreementRepositoryImpl (复用)
api          IdentityApi#isVerified(accountId) (跨模块只读接口，M2 mbw-billing 消费)
```

> **Application port 子层**：与既有 `application/port/` 模式一致（如 `AnonymizeStrategy`）— `CipherService` 与 `RealnameVerificationProvider` 接口定义在 `application.port`，实现注入由 Spring Bean 完成；domain 层不直接依赖 port（domain 零 framework 依赖原则）。

## 核心 use case 流程

### `QueryRealnameStatusUseCase.execute(accountId)`

```text
1. realnameProfileRepository.findByAccountId(accountId) → Optional<RealnameProfile>
2. 缺省 → return RealnameStatusResult(UNVERIFIED, null, null, null)
3. 命中：
   - status=VERIFIED → 解密 realName + idCardNo → mask → return VERIFIED + realNameMasked + idCardMasked + verifiedAt
   - status=PENDING/FAILED → return status + (failedReason)，不解密敏感字段
   - status=UNVERIFIED → 同 step 2
```

无事务 / 只读；无副作用。

### `InitiateRealnameVerificationUseCase.execute(InitiateRealnameCommand cmd)`

`@Transactional(rollbackFor = Throwable.class, isolation = SERIALIZABLE)`：

```text
1. accountRepository.findById(cmd.accountId).orElseThrow(...)
   account.status==FROZEN → throw AccountInFreezePeriodException → 403 ACCOUNT_IN_FREEZE_PERIOD
   account.status==ANONYMIZED → 不可达（无 access_token）；防御性 → throw AccountNotFoundException → 401

2. agreementVersion 非空校验 → throw AgreementRequiredException → 400 REALNAME_AGREEMENT_REQUIRED
   accountAgreementRepository.save(accountId, "realname-auth", agreementVersion, ip, now())

3. identityNumberValidator.validate(cmd.idCardNo)
   失败 → throw InvalidIdCardFormatException → 400 REALNAME_INVALID_ID_CARD_FORMAT

4. rateLimitService.check("realname:" + cmd.accountId, 24h, 5)
   超限 → throw RateLimitExceededException → 429 + Retry-After
   rateLimitService.check("realname:" + cmd.ip, 24h, 20)
   同上

5. realnameProfileRepository.findByAccountId(cmd.accountId) → existing?
   existing.status==VERIFIED → throw AlreadyVerifiedException → 409 REALNAME_ALREADY_VERIFIED

6. idCardHash = sha256(cmd.idCardNo + pepper)
   realnameProfileRepository.findByIdCardHash(idCardHash) → other?
     other.exists && other.accountId != cmd.accountId
        && other.status in {VERIFIED, PENDING}
        → throw IdCardOccupiedException → 409 REALNAME_ID_CARD_OCCUPIED

7. realName_enc = cipherService.encrypt(cmd.realName.bytes())
   idCardNo_enc = cipherService.encrypt(cmd.idCardNo.bytes())

8. providerBizId = UUID.randomUUID().toString()
   provider.initVerification(InitRequest{providerBizId, realName, idCardNo})  // 注：传明文，不可缓存
     timeout / 5xx → throw ProviderTimeoutException → 503 REALNAME_PROVIDER_TIMEOUT (事务回滚)
     biz error → throw ProviderErrorException → 502 REALNAME_PROVIDER_ERROR (事务回滚)
   返回 livenessUrl

9. profile = existing.orElseGet(() -> new RealnameProfile())
       .withAccountId(cmd.accountId)
       .withStatus(PENDING)
       .withRealNameEnc(realName_enc)
       .withIdCardNoEnc(idCardNo_enc)
       .withIdCardHash(idCardHash)
       .withProviderBizId(providerBizId)
       .withFailedReason(null).withFailedAt(null)
       .withUpdatedAt(now())
   realnameProfileRepository.save(profile)
     DataIntegrityViolation (id_card_hash UNIQUE 兜底) → throw IdCardOccupiedException → 409

10. // commented TODO: emit telemetry realname_init

11. return InitiateRealnameResult(providerBizId, livenessUrl)
```

**dev-bypass**：`provider` Bean 由 `BypassRealnameClient` 注入；step 8 不调阿里云，直接返 fixed `livenessUrl="bypass://verified|failed"`；step 11 后由 `ConfirmRealnameVerificationUseCase` 立刻按 `_FIXED_RESULT` env var 转 VERIFIED/FAILED（同步路径）。

### `ConfirmRealnameVerificationUseCase.execute(ConfirmCommand cmd)`

`@Transactional(rollbackFor = Throwable.class)`：

```text
1. profile = realnameProfileRepository.findByProviderBizId(cmd.providerBizId)
              .orElseThrow(NotFoundException → 404)
   profile.accountId != cmd.callerAccountId → 403 (越权)

2. profile.status==VERIFIED → return existing result (幂等：已写终态直接返回)
   profile.status==FAILED → return existing result

3. // status==PENDING → 调阿里云查权威结果
   result = provider.queryResult(cmd.providerBizId)
     timeout / 5xx → throw ProviderTimeoutException → 503 (status 不变 PENDING，客户端可重试)

4. switch result {
     PASSED:
        profile.withStatus(VERIFIED).withVerifiedAt(now()).withFailedReason(null)
        rateLimitService.reset("realname:" + cmd.callerAccountId)  // 成功后清零
     NAME_ID_NOT_MATCH:
        profile.withStatus(FAILED).withFailedReason(NAME_ID_MISMATCH).withFailedAt(now())
        rateLimitService.recordFailure("realname:" + cmd.callerAccountId)
     LIVENESS_FAILED:
        profile.withStatus(FAILED).withFailedReason(LIVENESS_FAILED).withFailedAt(now())
        rateLimitService.recordFailure(...)
     USER_CANCELED:
        profile.withStatus(FAILED).withFailedReason(USER_CANCELED).withFailedAt(now())
        // 用户取消不计 rate limit failure（与公安比对失败语义不同）
   }
   realnameProfileRepository.save(profile)

5. // commented TODO: emit telemetry realname_complete

6. return ConfirmResult(status, failedReason, verifiedAt)
```

幂等：终态后再次 GET `/verifications/{bizId}` 直接读 DB 不再调阿里云（FR-007）。

## 数据流（请求生命周期）

```text
HTTP GET /api/v1/realname/me
   ↓
RealnameController#queryMe(@AuthenticationPrincipal accountId)
   ↓
QueryRealnameStatusUseCase.execute(accountId)
   ↓
RealnameProfileRepository.findByAccountId(accountId)
   ↓ (Optional<RealnameProfile>)
   解密敏感字段 (仅 VERIFIED 路径) → mask
   ↓ (RealnameStatusResult)
RealnameController 转 RealnameStatusResponse
   ↓ JSON
HTTP 200 {status, realNameMasked?, idCardMasked?, verifiedAt?, failedReason?}
```

```text
HTTP POST /api/v1/realname/verifications {realName, idCardNo, agreementVersion}
   ↓
RealnameController#initiate
   ↓
InitiateRealnameVerificationUseCase.execute(cmd) [事务边界]
   ↓
{providerBizId, livenessUrl}
   ↓
RealnameInitiateResponse
   ↓
HTTP 200
```

错误路径：domain exception → `mbw-shared.web.GlobalExceptionHandler` → RFC 9457 ProblemDetail。

## 数据模型变更（Flyway V12）

### 新建表 `account.realname_profile`

```sql
-- V12__create_realname_profile_table.sql
CREATE TABLE account.realname_profile (
    id              BIGSERIAL PRIMARY KEY,
    account_id      BIGINT NOT NULL UNIQUE,
    status          VARCHAR(16) NOT NULL,
                    -- enum: UNVERIFIED, PENDING, VERIFIED, FAILED
    real_name_enc   BYTEA,
                    -- AES-GCM ciphertext (nullable in UNVERIFIED state)
    id_card_no_enc  BYTEA,
                    -- AES-GCM ciphertext
    id_card_hash    CHAR(64),
                    -- SHA-256(id_card_no || pepper); nullable when UNVERIFIED
    provider_biz_id VARCHAR(64),
                    -- aliyun returned biz id; nullable when UNVERIFIED
    verified_at     TIMESTAMP WITH TIME ZONE,
    failed_reason   VARCHAR(32),
                    -- enum: NAME_ID_MISMATCH, LIVENESS_FAILED, USER_CANCELED, PROVIDER_ERROR
    failed_at       TIMESTAMP WITH TIME ZONE,
    retry_count_24h INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_realname_status CHECK (status IN ('UNVERIFIED', 'PENDING', 'VERIFIED', 'FAILED'))
);

-- Partial unique on id_card_hash: 仅当 hash 非 NULL 时才唯一（UNVERIFIED 状态可有 NULL）
CREATE UNIQUE INDEX uk_realname_profile_id_card_hash
  ON account.realname_profile (id_card_hash)
  WHERE id_card_hash IS NOT NULL;

-- 查询索引
CREATE INDEX idx_realname_profile_provider_biz_id
  ON account.realname_profile (provider_biz_id);
```

> **`updated_at` 维护策略**：与既有 `account.account` 等表风格一致 — 列上 `DEFAULT now()` 兜首次插入；后续更新由 JPA `@PreUpdate` 在应用层显式写入。**不引入 PG trigger**（原 plan 草稿中提及的 `account.set_updated_at()` 函数在仓内不存在，且 trigger + JPA 双写会造成歧义）。如未来需要全模块统一切到 trigger 维护，会作为单独 follow-up 一次性 retro-fit 全表，不混在本 use case 里。
>
> **不加跨表 FK** 到 `account.account(id)` — 与既有 `login_audit`、`account_agreement`、`refresh_token` 表风格一致；通过应用层保证引用完整性，便于未来拆服务。

### Account 表不变

`account.account` 表无字段变更；`status` 字段 FROZEN 检查由 `AccountStateMachine` 复用。

### 注销联动（与 PRD § 5.5 amend 对齐）

`AnonymizeAccountUseCase`（既有）在匿名化 step 中追加：

```text
realnameProfileRepository.findByAccountId(accountId).ifPresent(profile -> {
    profile.withRealNameEnc(null)
           .withIdCardNoEnc(null)
           .withIdCardHash(null)        // 释放给新账号
           .withStatus(UNVERIFIED)
           .withProviderBizId(null)
           .withFailedReason(null);
    realnameProfileRepository.save(profile);
});
```

属于 `mbw-account/anonymize-frozen-accounts` 既有 spec 的扩展点；同 PR cycle 修改其 plan.md / tasks.md 标 amend。

## 内部决策（mini-ADR）

### D-001：阿里云产品选型

**Decision**：使用阿里云 **实人认证**（产品 ID `Cloud_authentication`，OpenAPI endpoint `cloudauth.aliyuncs.com`）— 标准方案 `RPBioOnly` 或 `RPBasic`（待 owner 在控制台开通时确认）。

**Alternatives**：
- 阿里云人脸核身（OCR + 活体）— v1 不需要 OCR
- 第三方（腾讯慧眼 / 网证 CTID）— 与既有阿里云生态不对齐

**Why**：与 ADR-0013（阿里云 SMS 牌照后启用）+ 阿里云 OSS / ECS 同生态；单云供应商；标准方案 `RPBasic` 含三要素 + 活体已覆盖 v1 需求。

### D-002：CipherService 抽象边界

**Decision**：`CipherService.encrypt(byte[]) → byte[]` / `decrypt(byte[]) → byte[]` 接口定义在 `application.port`；`EnvDekCipherService`（M1）和 `AliyunKmsCipherService`（M3 placeholder，本期仅写空 stub）实现在 `infrastructure.security`。

**Why**：
- 接口在 application.port → domain 层零依赖（domain 拿密文不解密）
- 切 KMS 时 plug-and-play，业务代码不动
- M1 stub 不实现避免 dependency 拉入；M3 PR 时再做
- AES-GCM 算法 / IV 长度等参数封装在 impl，调用方仅看 byte[]

### D-003：providerBizId 幂等键策略

**Decision**：服务端生成 UUID v4 作为 `providerBizId`；写库 → 调阿里云 → 客户端轮询同 bizId。

**Alternatives**：
- 客户端生成 idempotency key — 多一份 client 复杂度且不防 replay
- 阿里云返回的 BizId — 调用前未知，无法预先写库做幂等

**Why**：UUID 服务端可控；事务先写后调阿里云保证不丢 BizId；同 phone-sms 模式（先 hash 写 Redis 后发 SMS）。

### D-004：Repository 接口边界

**Decision**：`RealnameProfileRepository` 暴露 `findByAccountId` / `findByIdCardHash` / `findByProviderBizId` / `save`。**不暴露** `findAll` / `deleteById`（不可解绑约束的物理体现）。

**Why**：FR-015 不可解绑 → 接口表面就移除 delete；防 future code 误用。

### D-005：跨模块 IdentityApi 本期是否暴露

**Decision**：**本期暴露**只读接口 `IdentityApi#isVerified(accountId): boolean`，定义在 `mbw-account/api/service/IdentityApi.java`；implementation `IdentityApiImpl` 在 application 层注入 `RealnameProfileRepository`。

**Why**：
- 简单只读，零事务 / 零风险，本期暴露成本极低
- M2 `mbw-billing` 接入提现 / 达人入驻 gate 时立即可用，避免接入时还要回头补接口
- 不暴露写入接口（保持 use case 模块内独占写权限）

### D-006：Spring Modulith Event 本期是否引入

**Decision**：**本期不引入** `RealnameVerifiedEvent`；M2 mbw-billing 真消费时再加。

**Why**：YAGNI；当前无消费者，引入 event 仅为"以防万一"；M2 加 event 时 owner 也只需改一处（`ConfirmRealnameVerificationUseCase` 末尾 publish）。

### D-007：错误码分布（mbw-account vs mbw-shared）

**Decision**：所有 8 条 `REALNAME_*` 错误码归属 `mbw-account.api.error.AccountErrorCode` enum（与既有 `PHONE_ALREADY_REGISTERED` 同档）。复用 `RATE_LIMIT_EXCEEDED` / `ACCOUNT_IN_FREEZE_PERIOD` / `UNAUTHORIZED`（既有 `mbw-shared.api.error.SystemErrorCode` 或 account enum）。

**Why**：模块特有错误码归本模块（per server CLAUDE.md § 三）；`PROVIDER_*` 虽然是上游调用错误但语义只对实名场景有意义，不上提 shared。

## 复用既有基础设施（不新增）

- `RateLimitService`（既有 Redis backend per ADR-0011）— 新增 `realname:<accountId>` / `realname:<ip>` bucket 命名空间
- `AccountRepository#findById` + `AccountStateMachine`（既有）— FROZEN 检查
- ~~`AccountAgreementRepository`（既有 PRD § 5.7）— 协议同意写入~~ **drift #3 fix**（PR-3 T13 期间发现）：PR-1/PR-2 实际未交付该 Repository，仅有 `AgreementRequiredException` domain 异常；PRD § 5.7 协议存证子系统延后到 M3 单独 spec 立项。本期 T13 use case 仅做 `agreementVersion 非空校验 throw AgreementRequiredException`，不调用任何 repo。
- `mbw-shared.web.GlobalExceptionHandler` + RFC 9457 ProblemDetail
- `@AuthenticationPrincipal` resolver（Spring Security，既有）— accountId 注入
- Springdoc OpenAPI（既有）— 自动生成 spec
- Spring Modulith Event Publication Registry — 当前不用，M2+ 引入

## 新建组件

| 类 | 层 | 职责 |
|---|---|---|
| `RealnameProfile` | domain.model | 聚合根（含 status / encrypted fields / hash / providerBizId / 时间字段） |
| `RealnameStatus` | domain.model | enum (UNVERIFIED / PENDING / VERIFIED / FAILED) |
| `FailedReason` | domain.model | enum (NAME_ID_MISMATCH / LIVENESS_FAILED / USER_CANCELED / PROVIDER_ERROR) |
| `IdentityNumberValidator` | domain.service | GB 11643 校验 |
| `RealnameStateMachine` | domain.service | 状态转换合法性 |
| `RealnameProfileRepository` | domain.repository | 纯接口 |
| `CipherService` | application.port | 加解密抽象 |
| `RealnameVerificationProvider` | application.port | 阿里云调用抽象（init / queryResult） |
| `InvalidIdCardFormatException` | domain.exception | |
| `AlreadyVerifiedException` | domain.exception | |
| `IdCardOccupiedException` | domain.exception | |
| `AgreementRequiredException` | domain.exception | |
| `ProviderTimeoutException` | domain.exception | |
| `ProviderErrorException` | domain.exception | |
| `RealnameProfileJpaEntity` | infra.persistence | JPA mapping |
| `RealnameProfileJpaRepository` | infra.persistence | Spring Data JPA |
| `RealnameProfileMapper` | infra.persistence | MapStruct |
| `RealnameProfileRepositoryImpl` | infra.persistence | impl wiring JPA |
| `EnvDekCipherService` | infra.security | AES-GCM via env DEK |
| `AliyunKmsCipherService` | infra.security | M3 stub（@Profile("kms") + @Lazy） |
| `AliyunRealnameClient` | infra.client | 阿里云 SDK 调用（non-bypass） |
| `BypassRealnameClient` | infra.client | dev mock，按 `_FIXED_RESULT` 直返 |
| `RealnameVerificationProviderConfig` | infra.config | `@Profile` 路由 + prod fail-fast |
| `IdentityApi` | api.service | 跨模块只读 |
| `IdentityApiImpl` | application.service | impl |
| `QueryRealnameStatusUseCase` | application.usecase | |
| `InitiateRealnameVerificationUseCase` | application.usecase | |
| `ConfirmRealnameVerificationUseCase` | application.usecase | |
| `InitiateRealnameCommand` | application.command | record(accountId, realName, idCardNo, agreementVersion, ip) |
| `ConfirmRealnameCommand` | application.command | record(callerAccountId, providerBizId) |
| `RealnameStatusResult` / `InitiateRealnameResult` / `ConfirmResult` | application.result | record |
| `RealnameController` | web.controller | 3 endpoint 路由 |
| `RealnameInitiateRequest` | web.request | record + Jakarta Validation |
| `RealnameStatusResponse` / `RealnameInitiateResponse` / `RealnameConfirmResponse` | web.response | record |
| `RealnameProfileE2EIT` | test (mbw-app) | User Stories 1-4 + dev-bypass |
| `LoggingLeakIT` | test (mbw-app) | 跑全流程 grep logs 不命中明文 |
| `RealnameImmutabilityIT` | test (mbw-app) | DELETE / PATCH 405/404 |
| `IdCardOccupiedIT` | test (mbw-app) | 100 并发同号验 SC-003 |
| `RealnameRateLimitIT` | test (mbw-app) | FR-009 限流验证 |

## 删除组件

无（首次引入 use case，无 supersedes）。

## 测试策略

| 层 | 测试类 | 覆盖 |
|---|---|---|
| Domain unit | `IdentityNumberValidatorTest` | GB 11643 全表数据：合法 / 非法长度 / 非法字符 / 末位错 |
| Domain unit | `RealnameStateMachineTest` | 4 状态机合法转换矩阵；非法转换 throw |
| Domain unit | `RealnameProfileTest` | 聚合根不变式（withStatus / withVerifiedAt 等转换不漏字段） |
| Application unit | `QueryRealnameStatusUseCaseTest` | 4 状态分支；解密路径仅 VERIFIED 触发 |
| Application unit | `InitiateRealnameVerificationUseCaseTest` | 11 分支：FROZEN / 协议缺失 / 格式错 / 限流 / ALREADY_VERIFIED / ID_CARD_OCCUPIED 同账号 / ID_CARD_OCCUPIED 跨账号 / 阿里云超时 / 阿里云错 / DataIntegrity 兜底 / happy path |
| Application unit | `ConfirmRealnameVerificationUseCaseTest` | 5 分支：终态幂等 / PASSED / NAME_ID_NOT_MATCH / LIVENESS_FAILED / USER_CANCELED |
| Web unit | `RealnameControllerTest`（@WebMvcTest）| 3 endpoint 路径 + 错误码 mapping + Jakarta Validation |
| Integration | `RealnameProfileE2EIT` | User Stories 1-4 端到端，Testcontainers PG + Redis + WireMock(阿里云 mock) |
| Integration | `LoggingLeakIT` | 跑完整流程后 grep logs file 不命中身份证号 / 真实姓名 |
| Integration | `RealnameImmutabilityIT` | DELETE / PATCH `/api/v1/realname/me` → 404/405 |
| Integration | `IdCardOccupiedIT` | 100 thread 并发提交相同身份证号 → 仅 1 行写入；其余 409 |
| Integration | `RealnameRateLimitIT` | account 24h × 5 / ip 24h × 20 触发 429 |
| Integration | `RealnameAccountAnonymizeIT` | 注销 → ANONYMIZED 触发 → realname_profile 字段全 NULL（amend 既有 spec test） |
| ArchUnit / Modulith | `ModuleStructureTest`（既有）| 0 violation；新 use case 遵守 5 层 + cross-module rules |

TDD 节奏（per server CLAUDE.md § 一）：每 task 内先写 unit test 红 → 实现 → 绿 → 重构；最后写 IT 集成验证。

## API 契约（OpenAPI + 前端 client）

| Endpoint | Method | Tag | OpenAPI 备注 |
|---|---|---|---|
| `/api/v1/realname/me` | GET | Realname | 200 RealnameStatusResponse / 401 |
| `/api/v1/realname/verifications` | POST | Realname | 200 RealnameInitiateResponse / 400 / 403 / 409 / 422 / 429 / 502 / 503 |
| `/api/v1/realname/verifications/{providerBizId}` | GET | Realname | 200 RealnameConfirmResponse / 403 / 404 / 503 |

新增 OpenAPI tag "Realname"；前端 `pnpm api:gen` 后自动生成 `RealnameControllerApi` TS class。

## 配置项（application.yml + env vars）

| Key | 用途 | 默认 | prod 必需 |
|---|---|---|---|
| `MBW_REALNAME_DEK_BASE64` | AES-GCM master key（32 bytes base64） | （fail-fast 缺失启动失败） | ✅ |
| `MBW_REALNAME_HASH_PEPPER` | id_card_hash 用 pepper（≥ 32 字节） | （同上） | ✅ |
| `MBW_REALNAME_DEV_BYPASS` | dev 直通开关 | `false` | ❌（true 在 prod 启动 fail-fast） |
| `MBW_REALNAME_DEV_FIXED_RESULT` | dev 直通结果（`verified` / `failed`） | `verified` | ❌ |
| `MBW_ALIYUN_REALNAME_ACCESS_KEY_ID` | 阿里云 access key | （fail-fast）| ✅ |
| `MBW_ALIYUN_REALNAME_ACCESS_KEY_SECRET` | 阿里云 secret | （fail-fast）| ✅ |
| `MBW_ALIYUN_REALNAME_REGION_ID` | region | `cn-hangzhou` | ✅ |

prod fail-fast 实现：`@Configuration` + `ApplicationRunner` 启动时校验组合：

```text
if profile==prod && (DEV_BYPASS=true) → throw IllegalStateException ("dev-bypass disabled in prod")
if profile==prod && (DEK / PEPPER / aliyun key missing) → throw IllegalStateException
```

## Constitution Check

- ✅ Modular Monolith：mbw-account 自包含；新增跨模块接口仅暴露 `IdentityApi#isVerified`（read-only），无写入跨模块
- ✅ DDD 5 层：use case 编排，domain 不感知 framework，infra 实现 IO
- ✅ Repository 方式 A：`RealnameProfileRepository` domain 纯接口；JPA 实现在 infra
- ✅ TDD：所有新代码先写测试
- ✅ Spring Modulith Verifier：跨模块仅经过 `api` 包；本期 `IdentityApi` 在 `mbw-account.api.service`
- ✅ ArchUnit：domain 零 framework 依赖；CipherService 接口在 application.port 而非 domain（避免 domain 依赖 byte[] 算法实现细节）
- ✅ Schema 隔离：新表落 `account` schema；不与其他 schema 跨 FK
- ✅ expand-migrate-contract：V12 仅新建表，无破坏性 schema 变更（CREATE TABLE 自然兼容）
- ✅ OpenAPI 单一真相源：spec.md 不重复 data schema；Springdoc 注解自动生成

## 反模式（明确避免）

- ❌ domain 层引用 `javax.crypto.*` / `org.springframework.*` — 加密走 application.port `CipherService` 抽象
- ❌ 在 controller / use case 直接拼 mask 字符串 — mask 由 `RealnameProfile` 聚合根自身的 `maskedRealName()` / `maskedIdCardNo()` 方法负责（domain 知道 mask 规则）
- ❌ 解密敏感字段后 log / toString — `@ToString.Exclude` + `LoggingSafeRealnameProfile` 双层防护
- ❌ DELETE / PATCH `/api/v1/realname/*` — 不可解绑约束物理体现
- ❌ providerBizId 由客户端生成 — 服务端 UUID（D-003）
- ❌ 跨 schema FK 到 `account.account` — per modular-strategy.md
- ❌ 在 prod 环境启用 `MBW_REALNAME_DEV_BYPASS=true` — 启动 fail-fast
- ❌ 把 8 条 `REALNAME_*` 错误码上提到 `mbw-shared.SystemErrorCode` — 模块特有，留 `AccountErrorCode`

## References

- [`./spec.md`](./spec.md) / [`./tasks.md`](./tasks.md)
- [PRD § 5.10](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/requirement/account-center.v2.md#510-实名认证m1x-引入)
- [ADR-0001](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0001-modular-monolith.md) Modular Monolith
- [ADR-0008](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0008-repository-pure-interface.md) Repository 方式 A
- [ADR-0011](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0011-rate-limit-jcache-then-redis.md) RateLimit
- [ADR-0013](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/adr/0013-defer-sms-to-business-license.md) Aliyun ESP（同 vendor）
- 既有参考：[`spec/account/phone-sms-auth/plan.md`](../phone-sms-auth/plan.md) — 结构参照
- 既有参考：[`spec/account/anonymize-frozen-accounts/`](../anonymize-frozen-accounts/) — 注销联动落点
