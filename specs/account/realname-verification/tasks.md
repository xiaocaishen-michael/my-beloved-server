# Tasks: Realname Verification

**Spec**: [`./spec.md`](./spec.md) · **Plan**: [`./plan.md`](./plan.md)
**Phase**: M1.X
**Status**: Draft（pending impl）

> **TDD enforcement**：每个 [Application] / [Domain] / [Web] task 严格红 → 绿 → 重构（per server `CLAUDE.md` § 一）。每条 task 内**测试任务绑定到实现 task**，不独立列。
>
> **Status 标记**：task heading 无标记 = pending；加 `✅` = done；impl 每 task 完成同 commit 同步标记（per memory feedback `feedback_implement_owns_tasks_md_sync`）。
>
> **顺序**：T0 migration → T1-T7 domain + port + repo 接口（无 IO，可并行子任务）→ T8-T11 infrastructure（IO 实现）→ T12-T15 application + 跨模块 api → T16 web → T17-T21 集成测试 → T22-T23 联动 amend / OpenAPI 拉同步。

---

## T0 [Infrastructure/Migration]：V12 Flyway create_realname_profile_table ✅

**前置**：本 spec 三件套 PR merged + ADR / PRD § 5.10 已 ship（meta PR #65）

**子任务**（TDD：先红 → 再写 SQL → 绿）：

- T0.1 新建 `mbw-account/src/test/java/com/mbw/account/infrastructure/persistence/RealnameProfileSchemaIT.java`：`@Testcontainers` 启 PG + Flyway 跑全量 migration；4 个 @Test 直接断 `information_schema` / `pg_indexes`：
  - `v12_creates_realname_profile_table_with_13_columns()` — 13 字段名 + 类型断言
  - `v12_creates_partial_unique_index_on_id_card_hash()` — `uk_realname_profile_id_card_hash` 存在且 `WHERE id_card_hash IS NOT NULL`
  - `v12_creates_index_on_provider_biz_id()` — `idx_realname_profile_provider_biz_id` 存在
  - `v12_chk_realname_status_rejects_unknown_value()` — 直接 INSERT status='WAT' → SQLState 23514
  - 期望全 RED（V12 还没写）
- T0.2 新建 `mbw-account/src/main/resources/db/migration/account/V12__create_realname_profile_table.sql`，按 plan.md § 数据模型变更 SQL 落盘（**无 trigger** 版，per plan amend）
- T0.3 跑 `./mvnw -pl mbw-account test -Dtest=RealnameProfileSchemaIT` 全 GREEN
- T0.4 ~~跑 `./mvnw -pl mbw-account flyway:info`~~ — 项目未引入 `flyway-maven-plugin`，Flyway 仅在 Spring Boot 启动时 autoconfigure 应用；T0.3 IT 启动已隐式覆盖（V1-V12 全量应用成功 + DDL 形状断言通过即证明 V12 被检出 + 应用）。amend：本 task 不单独执行

**Verify**:

- DDL 落 `account.realname_profile` 表 + partial unique index `uk_realname_profile_id_card_hash` + index `idx_realname_profile_provider_biz_id` + check `chk_realname_status`
- `updated_at` 由 JPA `@PreUpdate` 在 T7 写入（per plan amend）；本 task 不落 trigger
- `RealnameProfileSchemaIT` 全绿

---

## T1 [Domain]：RealnameProfile 聚合根 + RealnameStatus / FailedReason enums ✅

**TDD**：先写 `RealnameProfileTest` 覆盖聚合根不变式 + mask 方法。

### T1-test：RealnameProfileTest

新建 `mbw-account/src/test/java/com/mbw/account/domain/model/RealnameProfileTest.java`：

| Test 场景 | Expect |
|---|---|
| 聚合根创建：`RealnameProfile.unverified(accountId)` | status=UNVERIFIED；其他敏感字段全 null |
| `withPending(realNameEnc, idCardNoEnc, idCardHash, providerBizId)` | status 转 PENDING；字段写入；createdAt 不变；updatedAt 更新 |
| `withVerified(verifiedAt)` | status=VERIFIED；verifiedAt 写入；failedReason/failedAt 清空 |
| `withFailed(reason)` | status=FAILED；failedReason / failedAt 写入；retryCount24h++ 仅当 reason != USER_CANCELED |
| `maskedRealName()` 2 字（"张三"）| `"*三"` |
| `maskedRealName()` 3 字（"张小明"）| `"**明"` |
| `maskedRealName()` 4 字（"欧阳询初"）| `"***初"` |
| `maskedIdCardNo()` 18 位 | 首位 + 16 个 `*` + 末位 |
| 非法 transition：UNVERIFIED → VERIFIED 直接转 | throw IllegalStateException |
| 非法：null realNameEnc 写入 PENDING | throw NullPointerException |

**Verify**: `./mvnw -pl mbw-account test -Dtest=RealnameProfileTest` 全 RED

### T1-impl：RealnameProfile + 2 enum + RealnameStateMachine 联动

新建：

- `domain/model/RealnameProfile.java`：record 或 immutable class，含 `withXxx()` 方法做状态转换；mask 规则封装（FR-008）
- `domain/model/RealnameStatus.java`：enum (UNVERIFIED, PENDING, VERIFIED, FAILED)
- `domain/model/FailedReason.java`：enum (NAME_ID_MISMATCH, LIVENESS_FAILED, USER_CANCELED, PROVIDER_ERROR)
- 不依赖任何 framework（per Constitution）；零 `@Entity` / `@Component`

**Verify**: T1-test 全 GREEN

---

## T2 [Domain]：IdentityNumberValidator (GB 11643) ✅

**TDD**：先写 `IdentityNumberValidatorTest`。

### T2-test：IdentityNumberValidatorTest

新建 `mbw-account/src/test/java/com/mbw/account/domain/service/IdentityNumberValidatorTest.java`：

| Test 场景 | Input | Expect |
|---|---|---|
| 合法：测试号 | `110101199001011237` | true |
| 合法：末位 X | `11010119900101004X` | true（GB 11643 sum=112, mod=2 → C[2]='X'） |
| 非法长度：17 位 | `1101011990010112` | false |
| 非法长度：19 位 | `1101011990010112370` | false |
| 非法字符：含字母 | `11010119900A011237` | false |
| 非法末位校验码 | `110101199001011230` | false（GB 11643 计算应为 7 不是 0）|
| 非法地区码：00 开头 | `001010199001011230` | false（前 2 位非法行政区划） |
| 非法日期：2 月 30 | `110101199002301234` | false |
| null / 空串 | null / "" / "   " | false |

**Verify**: 全 RED

### T2-impl：IdentityNumberValidator

新建 `domain/service/IdentityNumberValidator.java`：

- 静态方法 `boolean validate(String idCardNo)`
- 校验：长度 = 18 / 前 17 位数字 + 末位 0-9X / 行政区划码非 00 开头 / 日期合法 / GB 11643 末位校验码
- GB 11643 加权系数 `[7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2]`，校验码表 `["1","0","X","9","8","7","6","5","4","3","2"]`

**Verify**: T2-test 全 GREEN

---

## T3 [Domain]：RealnameStateMachine ✅

**TDD**：先写 test 覆盖合法 / 非法转换矩阵。

### T3-test

新建 `domain/service/RealnameStateMachineTest.java`：

| from → to | Expect |
|---|---|
| UNVERIFIED → PENDING | OK |
| PENDING → VERIFIED | OK |
| PENDING → FAILED | OK |
| FAILED → PENDING | OK（重试）|
| UNVERIFIED → VERIFIED | throw IllegalStateException |
| UNVERIFIED → FAILED | throw |
| VERIFIED → 任何 | throw（终态）|
| FAILED → VERIFIED | throw（必须经 PENDING）|

### T3-impl

新建 `domain/service/RealnameStateMachine.java`：单方法 `assertCanTransition(from, to)`，非法转换 throw `IllegalStateException`。

**Verify**: 全 GREEN

---

## T4 [Domain]：6 个新异常类 ✅

**TDD 例外**：纯异常类（继承 `RuntimeException`），无业务逻辑 → 不强制单测，由 use case 测试间接覆盖（per server CLAUDE.md § 一 TDD 例外）。

### T4-impl

新建 `domain/exception/`：

- `InvalidIdCardFormatException.java` extends RuntimeException
- `AlreadyVerifiedException.java`
- `IdCardOccupiedException.java`
- `AgreementRequiredException.java`
- `ProviderTimeoutException.java`
- `ProviderErrorException.java`

`AccountInFreezePeriodException` 已存在（由 expose-frozen-account-status 落地），复用。

**Verify**: `./mvnw -pl mbw-account compile` GREEN

---

## T5 [Domain]：RealnameProfileRepository 接口 ✅

**TDD 例外**：纯接口无逻辑。

### T5-impl

新建 `domain/repository/RealnameProfileRepository.java`：

```java
public interface RealnameProfileRepository {
    Optional<RealnameProfile> findByAccountId(long accountId);
    Optional<RealnameProfile> findByIdCardHash(String idCardHash);
    Optional<RealnameProfile> findByProviderBizId(String providerBizId);
    RealnameProfile save(RealnameProfile profile);
}
```

**不暴露** delete / findAll / count（per plan D-004 不可解绑约束物理化）。

**Verify**: compile GREEN

---

## T6 [Application/Port]：CipherService + RealnameVerificationProvider 接口 ✅

**TDD 例外**：纯接口。

### T6-impl

新建 `application/port/`：

- `CipherService.java`：`byte[] encrypt(byte[])` / `byte[] decrypt(byte[])`
- `RealnameVerificationProvider.java`：
  - `InitVerificationResult initVerification(InitVerificationRequest req)`
  - `QueryVerificationResult queryVerification(String providerBizId)`
- `RealnameVerificationDto`（record，附属于 provider port）：`InitVerificationRequest(providerBizId, realName, idCardNo)` / `InitVerificationResult(livenessUrl)` / `QueryVerificationResult(outcome enum, failureReason?)`

**Verify**: compile GREEN

---

## T7 [Infrastructure/Persistence]：JpaEntity + Mapper + JpaRepo + RepositoryImpl ✅

**TDD**：先写 `RealnameProfileRepositoryImplIT` （Testcontainers PG），再实现。

### T7-test：RealnameProfileRepositoryImplIT

新建 `mbw-app/src/test/java/com/mbw/account/infrastructure/persistence/RealnameProfileRepositoryImplIT.java`：

| Test 场景 | Expect |
|---|---|
| save + findByAccountId 往返 | 字段全部一致（含 bytea / hash） |
| findByIdCardHash 命中 | 返回 RealnameProfile |
| findByIdCardHash 未命中 | Optional.empty |
| findByProviderBizId 命中 | 返回 |
| save 同 account 第二次 | upsert（按 PK / unique account_id） |
| save 触发 unique 冲突（同 idCardHash 不同 account） | throw DataIntegrityViolationException |
| save UNVERIFIED 状态（hash null） | OK，partial unique index 不生效 |

**Verify**: 全 RED（实现还没有）

### T7-impl

新建 `infrastructure/persistence/`：

- `RealnameProfileJpaEntity.java`：`@Entity @Table(schema="account", name="realname_profile")`；`@Convert` 处理 enum；bytea 直接 byte[]
- `RealnameProfileJpaRepository.java`：`extends JpaRepository<RealnameProfileJpaEntity, Long>`；含 `findByAccountId` / `findByIdCardHash` / `findByProviderBizId`
- `RealnameProfileMapper.java`：`@Mapper(componentModel = "spring")`；JpaEntity ↔ Domain Model
- `RealnameProfileRepositoryImpl.java`：`@Repository`，wraps Jpa + Mapper

**Verify**: T7-test 全 GREEN

---

## T8 [Infrastructure/Security]：EnvDekCipherService

**TDD**：先写 `EnvDekCipherServiceTest`。

### T8-test

新建 `mbw-account/src/test/java/com/mbw/account/infrastructure/security/EnvDekCipherServiceTest.java`：

| Test 场景 | Expect |
|---|---|
| encrypt + decrypt 往返 | 明文一致 |
| 同明文加密两次 | 密文不同（IV 随机） |
| decrypt 篡改密文（改 1 byte） | throw（auth tag 不通过） |
| decrypt 错误 IV | throw |
| Init 时 DEK env 缺失 | throw IllegalStateException |
| Init 时 DEK 长度非 32 byte | throw |

**Verify**: 全 RED

### T8-impl

新建 `infrastructure/security/EnvDekCipherService.java`：

- `@Service` + `@ConditionalOnProperty(name="mbw.realname.cipher", havingValue="env-dek", matchIfMissing=true)`
- 读 `MBW_REALNAME_DEK_BASE64` env var，base64 decode 32 byte → AES-256 key
- AES-GCM mode，IV 12 byte 随机，auth tag 16 byte
- 密文格式：`[IV(12)] || [ciphertext + auth tag]`
- 同时：`AliyunKmsCipherService.java` stub 占位（`@ConditionalOnProperty(name="mbw.realname.cipher", havingValue="aliyun-kms")` + impl throw `UnsupportedOperationException("M3 placeholder")`）

**Verify**: T8-test 全 GREEN

---

## T9 [Infrastructure/Client]：BypassRealnameClient

**TDD**：先写 test。

### T9-test

新建 `mbw-account/src/test/java/com/mbw/account/infrastructure/client/BypassRealnameClientTest.java`：

| Test 场景 | env | Expect |
|---|---|---|
| init bypass | DEV_BYPASS=true / FIXED_RESULT=verified | 返回 livenessUrl=`bypass://verified` |
| init bypass failed | DEV_BYPASS=true / FIXED_RESULT=failed | 返回 livenessUrl=`bypass://failed` |
| query verified | bizId 任意 / FIXED=verified | outcome=PASSED |
| query failed | FIXED=failed | outcome=NAME_ID_NOT_MATCH |

### T9-impl

新建 `infrastructure/client/BypassRealnameClient.java`：

- `@Service` + `@ConditionalOnProperty(name="mbw.realname.dev-bypass", havingValue="true")`
- impl `RealnameVerificationProvider`
- 不调外部，按 `MBW_REALNAME_DEV_FIXED_RESULT` 直返

**Verify**: GREEN

---

## T10 [Infrastructure/Client]：AliyunRealnameClient

**TDD**：先写 test（Mockito-mock SDK，对齐 `AliyunSmsClientTest` 项目惯例 — spec amend 见下）。

### T10-test

新建 `mbw-account/src/test/java/com/mbw/account/infrastructure/client/AliyunRealnameClientTest.java`（**`Test` 不是 `IT`，路径在 mbw-account 模块同 impl** — spec amend：原 spec 让用 WireMock IT 放 mbw-app，但 1) AliyunSmsClientTest 已用 Mockito-mock SDK 风格成立惯例，启动开销远低于 WireMock；2) impl 落 mbw-account，IT 放 mbw-app 跨模块组织反惯例 (per RealnameProfileRepositoryImplIT 同模块前例)）：

| Test 场景 | SDK Stub | Expect |
|---|---|---|
| initVerification 成功 | `initFaceVerify` 返回 `code=Success` + `resultObject.certifyUrl` | 返回 `InitVerificationResult(livenessUrl=certifyUrl)` |
| initVerification 5xx | `initFaceVerify` throw `TeaException(statusCode=503)` | throw ProviderTimeoutException |
| initVerification 业务错（参数 invalid） | `initFaceVerify` 返回 `code=InvalidParameter` | throw ProviderErrorException |
| queryVerification 通过 | `describeVerifyResult` 返回 `verifyStatus=1` | outcome=PASSED |
| queryVerification 不通过（默认） | `describeVerifyResult` 返回 `verifyStatus=0` | outcome=NAME_ID_NOT_MATCH（**spec drift**：cloud-auth API 不返回 SubCode 细分语义，所有 verifyStatus=0 默认映射 NAME_ID_NOT_MATCH，原 spec 期望的 LivenessFailed / UserCanceled 在 prod 路径**无法精确区分** — 详见 SC-005 简化说明） |
| queryVerification 系统错 | `describeVerifyResult` 返回 `verifyStatus=2` | throw ProviderErrorException |
| queryVerification 待提交 | `describeVerifyResult` 返回 `verifyStatus=-1` | throw ProviderErrorException |
| queryVerification 超时 | `describeVerifyResult` throw `TeaException(statusCode=503)` | throw ProviderTimeoutException |

> **SC-005 简化说明（spec amend）**：原 FR-009 / SC-005 期望"USER_CANCELED 不增加 24h retry counter"。但 cloudauth20190307 的 `DescribeVerifyResultResponseBody` 仅含 `verifyStatus` (Integer) + scores + material 图片 URLs，**无法区分 USER_CANCELED / NAME_ID_NOT_MATCH / LIVENESS_FAILED**。M1 prod 路径所有 failed outcome 都 fall back 到 NAME_ID_NOT_MATCH，retry counter 一律自增。精确区分需切 callback 架构（initFaceVerify 设 callbackUrl + callbackToken），属另一个 spec 范围。BypassClient 仍按 4 值精确返回（dev 测试需要）。

### T10-impl

新建 `infrastructure/client/AliyunRealnameClient.java` + `AliyunRealnameClientConfig.java` + `AliyunRealnameProperties.java`：

- `AliyunRealnameClient`：`@Service` + `@ConditionalOnProperty(name="mbw.realname.dev-bypass", havingValue="false", matchIfMissing=true)`，impl `RealnameVerificationProvider`，构造器接 SDK `Client` + `AliyunRealnameProperties`（便于 Mockito mock SDK，对齐 `AliyunSmsClient` 风格）
- `AliyunRealnameClientConfig`：`@Configuration` 同 conditional，提供 `@Bean Client aliyunCloudauthClient(AliyunRealnameProperties)`，consumer-side 校验 accessKeyId / accessKeySecret / sceneId 非空（properties 不加 `@Validated` 避免 dev/test 启动失败 — 同 `MockSmsProperties` 风格）
- `AliyunRealnameProperties`：record `(accessKeyId, accessKeySecret, endpoint, sceneId)`，prefix=`mbw.realname.aliyun`，endpoint 默认 `cloudauth.aliyuncs.com`
- 阿里云 SDK 依赖：`com.aliyun:cloudauth20190307:3.13.2`（Apache-2.0，已 user 审议 ✅）落 `mbw-account/pom.xml`；同时把 `okhttp 5.3.2` CVE-2021-0341 override **从 `mbw-app/pom.xml` 上提到父 pom dependencyManagement**（与 netty / pgjdbc / bcprov 同模式，避免每模块复制粘贴 pin），mbw-account 引 explicit `okhttp` (无 version) 让 CVE override 进 compile classpath
- SDK API 映射（**spec drift fix**：原 spec 写 `InitVerify` + `DescribeVerifyResult` 是 simplified name；SDK 实际方法 `Client#initFaceVerify` + `Client#describeVerifyResult`，response 字段 `resultObject.certifyUrl`（非 `LivenessUrl`）+ `verifyStatus` Integer（非 `SubCode` enum））
- timeout：connect 3s / read 8s（`Config.setConnectTimeout` / `setReadTimeout`）
- 错误映射：`TeaException` 中 `statusCode>=500` → `ProviderTimeoutException`，其他 + 业务 code 非 Success / verifyStatus 非 {0,1} → `ProviderErrorException`
- 同时新增 `ProviderErrorException(String, Throwable)` + `ProviderTimeoutException(String, Throwable)` ctor（PR-1 只有 single-arg ctor，T10 wrap message 需要带 cause）
- IllegalCatch checkstyle 抑制：在 `config/checkstyle/checkstyle-suppressions.xml` 加 `AliyunRealnameClient.java`（与 `AliyunSmsClient` 同 supression 同理由 — SDK declared `throws Exception`，integration boundary 必须 catch Throwable 再 re-map）

**Verify**: GREEN

---

## T11 [Infrastructure/Config]：dev-bypass routing + prod fail-fast

**TDD**：先写 ApplicationContext startup test。

### T11-test

新建 `mbw-account/src/test/java/com/mbw/account/infrastructure/config/RealnameProviderConfigIT.java`（**spec amend**：路径 mbw-app → mbw-account，与 impl 同模块；用 Spring Boot `ApplicationContextRunner` 跑 5 个 slim-context 场景，无需 `@SpringBootTest` 启 PG）：

| Test 场景 | Expect |
|---|---|
| dev profile + DEV_BYPASS=true | startup OK，注入 BypassRealnameClient，无 AliyunRealnameClient bean |
| dev profile + DEV_BYPASS=false（须提供 dummy aliyun creds 让 SDK Client bean 能创建）| startup OK，注入 AliyunRealnameClient，无 BypassRealnameClient bean |
| prod profile + DEV_BYPASS=true | startup fail，rootCause 含 "dev-bypass=true is forbidden" |
| prod profile + DEK 缺失 | startup fail，rootCause 含 "mbw.realname.dek.base64" |
| prod profile + ALIYUN access-key-id 缺失 | startup fail，rootCause 含 "access-key-id" |

### T11-impl

新建 `infrastructure/config/RealnameProviderConfig.java` + `RealnameProviderStartupValidator.java`：

- `RealnameProviderConfig`：`@Configuration`，仅声明 `@Bean RealnameProviderStartupValidator(Environment env)`
- `RealnameProviderStartupValidator`：构造器 fail-fast — prod profile 下校验 DEV_BYPASS≠true + DEK / aliyun access-key-id / aliyun access-key-secret / aliyun scene-id 全在；非 prod profile skip 全部检查
- 缺失即 throw `IllegalStateException`

**Spec amend** (validator 风格)：原 spec 用 `@Bean ApplicationRunner`，但 ApplicationRunner.run() 在 context 完全 refresh 后才跑，**对 fail-fast 来说太晚**（k8s liveness probe 可能已报告 Pod healthy）。改为 `@Bean RealnameProviderStartupValidator` 在构造器抛 — bean creation 阶段直接 fail context.refresh()，与 `JwtProperties` compact ctor / `EnvDekCipherService` parseKey fail-fast 同模式。原 spec 还提到校验 PEPPER，但 PR-1 / PR-2 都未引入 pepper 配置（id_card_hash 的 hash 实现属 PR-3 use case 范围），本 task 不预先加 PEPPER 校验。

**Verify**: GREEN

---

## T12 [Application]：QueryRealnameStatusUseCase

**TDD**：先写 unit test。

### T12-test

新建 `application/usecase/QueryRealnameStatusUseCaseTest.java`：

| 场景 | Mock | Expect |
|---|---|---|
| 无 profile | repo.findByAccountId → empty | result.status=UNVERIFIED；其他字段 null |
| VERIFIED | repo 返回 VERIFIED profile | cipher.decrypt 调 2 次（姓名 + 号）；mask 字段填充 |
| PENDING | repo 返回 PENDING | result.status=PENDING；不解密 |
| FAILED | 同上 | result.status=FAILED + failedReason；不解密 |
| 解密 throw | cipher.decrypt → throw | bubble up exception |

### T12-impl

新建 `application/usecase/QueryRealnameStatusUseCase.java`：按 plan.md § 核心 use case 流程实现。

**Verify**: GREEN

---

## T13 [Application]：InitiateRealnameVerificationUseCase

**TDD**：先写 unit test 覆盖 11 分支。

### T13-test

新建 `application/usecase/InitiateRealnameVerificationUseCaseTest.java`：覆盖 plan.md § 核心 use case 流程的 11 分支（FROZEN / 协议缺失 / 格式错 / 限流 account / 限流 ip / ALREADY_VERIFIED / ID_CARD_OCCUPIED 跨账号 / 阿里云超时 / 阿里云错 / DataIntegrity 兜底 / happy）。Mock：`TransactionTemplate` / `AccountRepository` / `RealnameProfileRepository` / `IdentityHashService` / `RateLimitService` / `CipherService` / `RealnameVerificationProvider`。

**Spec amend**（mock 清单 + drift #3 / B+1 事务）：

- 原列 `IdentityNumberValidator` 是 static 工具类（不可 mock），从 mock 清单移除；测试用合法身份证号走真实校验，invalid 分支用反例字符串触发 false 返回。
- 原列 `AccountAgreementRepository` per drift #3 不再使用：T13 use case 仅做 `agreementVersion 非空校验 throw AgreementRequiredException`，不做协议存证（M3 单独 spec 立项）。从 mock 清单移除。
- 加 `IdentityHashService`（PR-3 prereq B1 已注入）。
- 加 `TransactionTemplate`（BASE B+1 拆事务，见 T13-impl amend）。

**Verify**: 全 RED

### T13-impl

新建 `application/usecase/InitiateRealnameVerificationUseCase.java` + `application/command/InitiateRealnameCommand.java` + `application/result/InitiateRealnameResult.java`。

**Spec amend**（事务边界 = BASE B+1，覆盖原 `@Transactional(rollbackFor = Throwable.class, isolation = SERIALIZABLE)` 单事务设计）：

- 改用 `TransactionTemplate` 显式拆两段事务。Tx1：validate + write PENDING row → commit；HTTP `provider.initVerification(...)` 在事务**外**；HTTP 失败 → Tx2（compensation）：mark PENDING → FAILED + `PROVIDER_ERROR`，再 rethrow 原 `ProviderTimeoutException` / `ProviderErrorException`。
- 理由：阿里云 init 调用 ~150ms-3s，包入单事务会持锁过久；用户拍板"倾向 BASE 理论"。
- compensation 仅 catch `ProviderTimeoutException | ProviderErrorException`（Checkstyle `IllegalCatch` 禁 catch RuntimeException）；其他未预期异常由 T13b PendingRealnameRecoveryScheduler 兜底。
- compensation 用 `withFailed(PROVIDER_ERROR, now)` 而非 PENDING→UNVERIFIED（RealnameStateMachine 不允许此回滚）。`PROVIDER_ERROR` 不递增 `retryCount24h`（PR-3 prereq A 已落地）。

**Verify**: T13-test 全 GREEN

---

## T13b [Infrastructure]：PendingRealnameRecoveryScheduler

**TDD**：先写 unit test，repo 新方法配 IT。

### T13b-repo

`RealnameProfileRepository` domain 接口新增：

```java
List<RealnameProfile> findStalePendingOlderThan(Instant threshold, int limit);
```

`RealnameProfileJpaRepository` 加 `@Query` JPQL：`status='PENDING' AND updatedAt < :threshold ORDER BY updatedAt ASC` + `Pageable` 限 limit。`Impl` 通过 `PageRequest.of(0, limit)` 调用并 `map(RealnameProfileMapper::toDomain)`。

`RealnameProfileRepositoryImplIT` 加 2 个 IT：
1. `findStalePendingOlderThan_returns_only_PENDING_rows_older_than_threshold` — 3 行（STALE PENDING / FRESH PENDING / VERIFIED 老）只返回 STALE PENDING
2. `findStalePendingOlderThan_orders_by_updatedAt_ascending_and_respects_limit` — 3 行 stale，limit=2 返回最老 2 行

### T13b-test

新建 `infrastructure/scheduling/PendingRealnameRecoverySchedulerTest.java`，8 cases：
- 空 stale list → 不调 provider，不 save
- PASSED → save with VERIFIED
- NAME_ID_NOT_MATCH → save with FAILED+NAME_ID_MISMATCH
- LIVENESS_FAILED → save with FAILED+LIVENESS_FAILED
- USER_CANCELED → save with FAILED+USER_CANCELED
- ProviderTimeoutException → swallow，不 save，timeout counter +1
- ProviderErrorException → save with FAILED+PROVIDER_ERROR，errors counter +1
- 多行 + 一个 timeout 一个 PASSED → 互不影响

**Verify**: 全 RED

### T13b-impl

新建 `infrastructure/scheduling/PendingRealnameRecoveryScheduler.java`，mirror `FrozenAccountAnonymizationScheduler` 风格：

- `@Component`，构造注入 + 包私 ctor 注入 `Clock`（测试可控）
- `@Scheduled(fixedRate = 5L * 60L * 1000L)` — compile-time 常量，不能用 `Duration.ofMinutes(5).toMillis()`（annotation 元素必须 constant expression）
- 常量：`STALE_THRESHOLD = Duration.ofMinutes(10)`, `LIMIT = 100`
- 流程：
  1. `now = Instant.now(clock)`，`threshold = now.minus(STALE_THRESHOLD)`
  2. `stale = repo.findStalePendingOlderThan(threshold, LIMIT)`
  3. for-each row：
     - try `provider.queryVerification(bizId)` → applyOutcome → `repo.save(updated)`
     - catch `ProviderTimeoutException` → swallow + log WARN，next tick 再试
     - catch `ProviderErrorException` → save `withFailed(PROVIDER_ERROR, now)` + log WARN
- `applyOutcome` 用 switch expression 映射 4 个 Outcome → 状态转换
- Micrometer counters：`account.realname.recovery.{scanned,recovered,timeouts,errors}`

**Verify**: T13b-test 全 GREEN + T13b-repo IT 全 GREEN

---

## T14 [Application]：ConfirmRealnameVerificationUseCase

### T14-test

新建 `ConfirmRealnameVerificationUseCaseTest.java`：5 分支（终态幂等 / PASSED / NAME_ID_NOT_MATCH / LIVENESS_FAILED / USER_CANCELED）+ 越权（callerAccountId != profile.accountId → 403）。

### T14-impl

新建 `application/usecase/ConfirmRealnameVerificationUseCase.java` + `application/command/ConfirmRealnameCommand.java` + `application/result/ConfirmResult.java`。

按 plan.md § 核心 use case 流程实现；幂等：终态后再次调用直接读 DB 不调阿里云。

**Verify**: GREEN

---

## T15 [Cross-module/api]：IdentityApi 跨模块只读接口

**TDD 例外**：接口 + 直 forward 实现，由后续 use case test 间接覆盖。

### T15-impl

新建：

- `mbw-account/src/main/java/com/mbw/account/api/service/IdentityApi.java`：

  ```java
  public interface IdentityApi {
      boolean isVerified(long accountId);
  }
  ```

- `application/service/IdentityApiImpl.java`：`@Service` impl，注入 `RealnameProfileRepository`，仅查 `findByAccountId(...).map(p -> p.status() == VERIFIED).orElse(false)`

**Verify**: `./mvnw -pl mbw-app verify` 仍绿；ArchUnit / Modulith 接受跨模块 api 暴露

---

## T16 [Web]：RealnameController + DTOs

**TDD**：先写 `@WebMvcTest`。

### T16-test

新建 `mbw-account/src/test/java/com/mbw/account/web/controller/RealnameControllerTest.java`：

`@WebMvcTest(RealnameController.class)` + `@MockBean` 3 个 use case：

| Test 场景 | Expect |
|---|---|
| GET /me 200 | mock QueryUseCase return → response 含 mask 字段 |
| POST /verifications 200 | mock InitiateUseCase return → response 含 providerBizId |
| GET /verifications/{bizId} 200 | mock ConfirmUseCase return |
| 401（@AuthenticationPrincipal null）| Spring Security 兜底 |
| 400 INVALID_ID_CARD_FORMAT | InitiateUseCase throw → ProblemDetail |
| 400 AGREEMENT_REQUIRED | 同上 |
| 403 ACCOUNT_IN_FREEZE_PERIOD | 同上 |
| 409 ALREADY_VERIFIED | 同上 |
| 409 ID_CARD_OCCUPIED | 同上 |
| 429 RATE_LIMIT_EXCEEDED | 同上 + Retry-After header |
| 502 PROVIDER_ERROR | 同上 |
| 503 PROVIDER_TIMEOUT | 同上 |
| Jakarta Validation：null realName | 400 + field error |
| DELETE /me | 405（Spring routing 默认） |
| PATCH /me | 405 |

### T16-impl

新建：

- `web/controller/RealnameController.java`：3 endpoint；`@AuthenticationPrincipal AccountId` 获取 caller
- `web/request/RealnameInitiateRequest.java`：record + Jakarta `@NotBlank` / `@Size`
- `web/response/RealnameStatusResponse.java`、`RealnameInitiateResponse.java`、`RealnameConfirmResponse.java`：record
- Springdoc `@Operation` + `@ApiResponse` 注解（per Springdoc 自动生成 OpenAPI）

**Verify**: T16-test 全 GREEN

---

## T17 [Test/IT]：RealnameProfileE2EIT

**前置**：T0-T16 全绿

新建 `mbw-app/src/test/java/com/mbw/account/integration/RealnameProfileE2EIT.java`：

Testcontainers PG + Redis + WireMock（或 BypassRealnameClient）；覆盖 spec.md User Stories 1-4 全部 acceptance scenario：

| US | 场景 |
|---|---|
| US-1 | UNVERIFIED → POST → PENDING → bypass query → VERIFIED → GET /me 返回 mask 字段 |
| US-1 | DB 字段：status=VERIFIED + 加密 bytea 非 null + idCardHash 非 null + verifiedAt 非 null |
| US-2 | VERIFIED 账号回访 GET /me 返回 readonly；POST → 409 ALREADY_VERIFIED |
| US-3 | UNVERIFIED → POST → bypass FIXED_RESULT=failed → query → FAILED + failedReason；再 POST → PENDING（重试）→ FIXED_RESULT=verified → VERIFIED |
| US-3 | retry_count_24h ≥ 5 → 第 6 次 POST 429 |
| US-4 | 账号 A VERIFIED + 账号 B 同号 POST → 409 ID_CARD_OCCUPIED + B 不写库 |
| Edge | FROZEN 账号 POST → 403 ACCOUNT_IN_FREEZE_PERIOD |
| Edge | 协议 agreementVersion=null → 400 AGREEMENT_REQUIRED |
| Edge | idCardNo 末位错 → 400 INVALID_ID_CARD_FORMAT |

**Verify**: GREEN

---

## T18 [Test/IT]：LoggingLeakIT

新建 `mbw-app/src/test/java/com/mbw/account/integration/LoggingLeakIT.java`：

- 配置临时 logback file appender → 写到 `target/test-logs/realname-leak-test.log`
- 跑 US-1 + US-3 + US-4 完整流程（覆盖成功 / 失败 / 409 路径）
- 跑后 `Files.readString(logPath)`：
  - assert NOT contains 测试身份证号 `110101199001011237`
  - assert NOT contains 测试姓名 `张三`
  - assert NOT contains hex string of idCardHash（防 hash 也泄漏）
  - assert contains accountId / providerBizId / `status transition` / `failedReason` enum 名

**Verify**: GREEN（per SC-002）

---

## T19 [Test/IT]：RealnameImmutabilityIT

新建 `mbw-app/src/test/java/com/mbw/account/integration/RealnameImmutabilityIT.java`：

- 预置 VERIFIED 账号
- DELETE `/api/v1/realname/me` → 405 Method Not Allowed
- DELETE `/api/v1/realname/verifications/{bizId}` → 405 / 404
- PATCH `/api/v1/realname/me` → 405
- PUT `/api/v1/realname/me` → 405
- 同样的 OpenAPI spec 中无 DELETE / PATCH operation（jq query Springdoc generated openapi.json）

**Verify**: GREEN（per SC-007）

---

## T20 [Test/IT]：IdCardOccupiedIT

新建 `mbw-app/src/test/java/com/mbw/account/integration/IdCardOccupiedIT.java`：

- 创建 100 个 ACTIVE 账号
- 100 thread 并发：所有账号 POST `/verifications` 提交相同身份证号
- 等所有完成
- 断言：DB `realname_profile` 总行数 ≤ 100；其中 status ∈ {PENDING, VERIFIED} 的行 = 1（同 idCardHash UNIQUE）；其余 99+ 请求 HTTP 409

**Verify**: GREEN（per SC-003）

---

## T21 [Test/IT]：RealnameRateLimitIT

新建 `mbw-app/src/test/java/com/mbw/account/integration/RealnameRateLimitIT.java`：

| 场景 | Expect |
|---|---|
| 同 account 24h 内 5 次 FAILED transition | 第 6 次 POST `/verifications` 429 + Retry-After |
| 同 ip 24h 内 20 次 transition | 第 21 次 429 |
| 成功 transition 后计数重置 | 5 次 FAILED + 1 次成功 → 之后 5 次 FAILED 仍可（计数清零） |
| USER_CANCELED 不计 failure | 5 次 USER_CANCELED 后再 1 次正常 POST 不锁 |

**Verify**: GREEN（per SC-005）

---

## T22 [Spec/amend]：anonymize-frozen-accounts spec amend（注销联动）

amend `mbw-account/specs/account/anonymize-frozen-accounts/spec.md` + `plan.md` + `tasks.md`：

- spec FR：追加"匿名化时清空 realname_profile.real_name_enc / id_card_no_enc / id_card_hash → NULL；status → UNVERIFIED；providerBizId → NULL"
- plan：在匿名化流程末尾追加 step
- tasks：追加 1 个 task `T-amend-realname` 改 `AnonymizeAccountUseCase` + 单测

**实施时机**：本 use case impl 阶段 T22 与 T0-T21 并行；M2+ 真有匿名化 cron 跑通时再补完整 IT。

**Verify**: anonymize-frozen-accounts 既有 IT 不破坏；新增 `RealnameAccountAnonymizeIT` 验联动

---

## T23 [Doc/OpenAPI sync]：前端 client 拉新

amend post-impl：

- 后端 ship 后 owner 在 app 仓 `pnpm api:gen` 拉新 OpenAPI spec
- 自动生成 `lib/api/RealnameApi.ts` 类
- 前端 spec PR（apps/native/specs/realname/）impl 阶段引用此 client

不在本 PR 内做（属下游 app 仓工作）；本任务仅声明依赖关系。

---

## 实施记录（impl ship 后填写，per memory `feedback_implement_owns_tasks_md_sync`）

> 待 Phase 2 implement 阶段每个 task ship 后回填 PR # / commit ref + 标 ✅。

- **T0** ✅ — V12 migration + RealnameProfileSchemaIT (4 tests GREEN)；plan amend：移除 `set_updated_at` trigger（仓内函数不存在，且与 JPA `@PreUpdate` 双写冲突）；tasks amend：T0.4 `flyway:info` 命令删除（项目无 `flyway-maven-plugin`，autoconfigure 已隐式覆盖）。Branch: `feature/realname-server-impl-pr1-domain-repo`. Commit: `2a0fb94`
- **T1** ✅ — `RealnameProfile`（immutable class，`unverified()` factory + `withPending/withVerified/withFailed` 状态转换 + 静态 `maskRealName/maskIdCardNo`）+ `RealnameStatus` / `FailedReason` enum；`RealnameProfileTest` 10 tests GREEN（factory + 3 transition + 4 mask + 1 illegal transition + 1 NPE guard）。inline state-machine 校验放 `RealnameProfile.requireLegalTransition`，T3 时 extract 到 `RealnameStateMachine`。mask 方法选择 **静态** 而非 instance method — domain 不存明文，UseCase 解密后调静态方法语义最干净。Commit: `8ee49d0`
- **T2** ✅ — `IdentityNumberValidator`（静态 `validate(String) → boolean`，校验顺序 长度→字符→地区码→日期→GB 11643）；`IdentityNumberValidatorTest` 11 tests GREEN（含 `@ParameterizedTest` 处理多输入）。tasks amend：原 X-末位测试号 `11010119900101001X` 实际 GB 11643 mod=7（应 5），不合法 → 修为 `11010119900101004X`（mod=2 → C[2]='X' 真合法）。impl 注意点：`DateTimeFormatter` 用 `uuuuMMdd`（proleptic-year）而非 `yyyyMMdd`（year-of-era），后者在 STRICT 模式需 era 字段，会拒所有合法日期。Commit: `35f3657`
- **T3** ✅ — `RealnameStateMachine`（静态 `assertCanTransition(from, to)`，extract from T1 内联 `requireLegalTransition`）；`RealnameStateMachineTest` 10 tests GREEN（@ParameterizedTest 4 legal + 6 illegal 矩阵）；`RealnameProfile.with*` refactor 改调 `RealnameStateMachine.assertCanTransition`，删 inline 私有方法。20/20 tests pass（10 RealnameProfileTest + 10 RealnameStateMachineTest）。Commit: `8a5698f`
- **T4** ✅ — 6 个 domain exception 类（`InvalidIdCardFormatException` / `AlreadyVerifiedException` / `IdCardOccupiedException` / `AgreementRequiredException` / `ProviderTimeoutException` / `ProviderErrorException`），全部 extends RuntimeException + `public static final String CODE`，跟既有 `InvalidPhoneFormatException` 风格一致。注意：`InvalidIdCardFormatException` **不持有** submitted ID number（PII，FR-008 / SC-002 防 log 泄漏）；`Provider*` 接 `Throwable cause` wrap 上游 SDK error。`./mvnw -pl mbw-account compile` GREEN。Commit: `fad6db6`
- **T5** ✅ — `RealnameProfileRepository` domain 接口（4 方法：`findByAccountId` / `findByIdCardHash` / `findByProviderBizId` / `save`，per D-004 不暴露 delete/findAll/count）。javadoc 注意：避开 `{@link spring class}` 引用（domain 零 framework 依赖原则），改纯文本描述。compile GREEN。Commit: `f276556`
- **T6** ✅ — application/port 下 5 个 type：`CipherService`（encrypt/decrypt byte[]） + `RealnameVerificationProvider`（initVerification/queryVerification） + 3 个 record DTO（`InitVerificationRequest` / `InitVerificationResult` / `QueryVerificationResult` 含 `Outcome` 嵌套 enum 4 值）。`Outcome` 嵌套在 result record 内（namespace 收敛于 query 语境）。`InitVerificationRequest` javadoc 显式标注 plaintext lifetime 边界（不缓存 / 不日志 / 不序列化）。compile GREEN。Commit: `004c0b0`
- **T7** ✅ — `RealnameProfileJpaEntity` + `RealnameProfileJpaRepository` + `RealnameProfileMapper`（hand-rolled，**不用 MapStruct**）+ `RealnameProfileRepositoryImpl`；domain 加 `RealnameProfile.reconstitute(...)` 工厂供 mapper 用。`RealnameProfileRepositoryImplIT` 7 tests GREEN（save round-trip / find* / upsert / unique violation / partial unique）。tasks amend：(1) IT 路径改 `mbw-account/src/test/...`（与既有 `AccountRepositoryImplIT` 一致；原 spec 写 mbw-app 路径不符既有约定）；(2) Mapper 实现风格用 hand-rolled（既有 `AccountMapper` javadoc 已声明项目不用 MapStruct，理由是 immutable + custom factory + VO 与 MapStruct 生成代码组合差）。Commit: `d97032f`

> **PR-1 (T0-T7) 完成** — branch `feature/realname-server-impl-pr1-domain-repo`，8 commits（2a0fb94 → d97032f），mbw-account 模块 380 tests 全 GREEN。等待 ship 决策（push + 开 PR）。T8+ (infra cipher / client / config) 属 PR-2 范围。

---

## References

- [`./spec.md`](./spec.md) / [`./plan.md`](./plan.md)
- 既有参考：[`specs/account/phone-sms-auth/tasks.md`](../phone-sms-auth/tasks.md) — 拆分粒度参照
- 既有参考：[`specs/account/anonymize-frozen-accounts/`](../anonymize-frozen-accounts/) — T22 amend 落点
