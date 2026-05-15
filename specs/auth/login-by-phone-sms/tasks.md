# Implementation Tasks: Login by Phone SMS

**Use case**: `login-by-phone-sms`
**Spec**: [`./spec.md`](./spec.md)
**Plan**: [`./plan.md`](./plan.md)
**Estimated total**: ~10-12h（实现 + 测试 + IT 起容器）；显著少于 register-by-phone（30h）因为复用大头基础设施

> **TDD 节奏**：每个 task 内严格红绿循环。任务标签：`[Migration]` / `[Domain]` / `[Infra]` / `[App]` / `[Web]` / `[E2E]` / `[Performance]` / `[Contract]`。

## Critical Path（按依赖顺序）

### T0 [Migration] V3__add_account_last_login_at.sql

**File**: `mbw-account/src/main/resources/db/migration/account/V3__add_account_last_login_at.sql`

**Logic**: `ALTER TABLE account.account ADD COLUMN last_login_at TIMESTAMP WITH TIME ZONE NULL;`

**Test**: 在本地起容器后跑 Flyway migration 看是否成功；`DESCRIBE account.account` 含新列。

**Dependencies**: 无。可与 T1/T2 并行。

---

### T1 [Domain] Account.lastLoginAt + markLoggedIn

**File**: `mbw-account/src/main/java/com/mbw/account/domain/model/Account.java`

**Logic**:

- 加字段 `private final Instant lastLoginAt;`（nullable）
- 加 `markLoggedIn(Instant now)` 方法，对 non-ACTIVE 抛 `IllegalStateException`，ACTIVE 返回 with-updated `lastLoginAt`

**Test**: `AccountTest`（已有，扩展）

- `should_throw_when_markLoggedIn_called_on_FROZEN_account()`
- `should_return_account_with_updated_lastLoginAt_when_ACTIVE()`

**Dependencies**: 无。可与 T0/T2 并行。

---

### T2 [Domain] AccountRepository.updateLastLoginAt 接口

**File**: `mbw-account/src/main/java/com/mbw/account/domain/repository/AccountRepository.java`

**Logic**: 加 `void updateLastLoginAt(AccountId accountId, Instant lastLoginAt);` 方法签名。纯接口，无实现。

**Test**: 接口无测；T4 实现层测。

**Dependencies**: 无。可与 T0/T1 并行。

---

### T3 [Domain] AccountStateMachine.canLogin

**File**: `mbw-account/src/main/java/com/mbw/account/domain/service/AccountStateMachine.java`

**Logic**: 加 `boolean canLogin(Account account)` 方法，仅 `ACTIVE` 返回 true，其他 false。

**Test**: `AccountStateMachineTest`（已有，扩展）

- `should_return_true_when_status_is_ACTIVE()`
- `should_return_false_when_status_is_FROZEN()` (FROZEN 状态机引入要等后续 use case，先用 stub / 反射注入)
- `should_return_false_when_status_is_ANONYMIZED()` (同上)

**Dependencies**: 无。可与 T0/T1/T2 并行。

---

### T4 [Infra] AccountJpaEntity + AccountRepositoryImpl.updateLastLoginAt

**Files**:

- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/AccountJpaEntity.java`
- `mbw-account/src/main/java/com/mbw/account/infrastructure/persistence/AccountRepositoryImpl.java`

**Logic**:

- JPA Entity 加 `@Column(name = "last_login_at") private Instant lastLoginAt;`（nullable）
- AccountMapper 加 lastLoginAt domain ↔ JPA 映射
- `AccountRepositoryImpl.updateLastLoginAt` 实现：`UPDATE` 查询单字段 + `updated_at` 同步

**Test**: `AccountRepositoryImplIT`（已有，扩展），Testcontainers PG。

- `should_update_last_login_at_when_called_on_existing_account()`
- `should_throw_when_account_id_not_found()`（保险，虽然外层已 findByPhone）

**Dependencies**: T0（migration）+ T1（domain field）+ T2（接口）。

---

### T5 [App] LoginByPhoneSmsCommand + LoginByPhoneSmsResult

**Files**:

- `mbw-account/src/main/java/com/mbw/account/application/command/LoginByPhoneSmsCommand.java`
- `mbw-account/src/main/java/com/mbw/account/application/result/LoginByPhoneSmsResult.java`

**Logic**:

- `LoginByPhoneSmsCommand`: `record(PhoneNumber phone, String code)`
- `LoginByPhoneSmsResult`: `record(long accountId, String accessToken, String refreshToken)`

**Test**: 纯 record，无逻辑；TDD 例外。

**Dependencies**: 无。可与 T0-T4 并行。

---

### T6 [App] LoginByPhoneSmsUseCase

**File**: `mbw-account/src/main/java/com/mbw/account/application/usecase/LoginByPhoneSmsUseCase.java`

**Logic**: per `plan.md` § UseCase 段，6 步执行链 + `@Transactional` + TimingDefenseExecutor wrap。

**Test**: `LoginByPhoneSmsUseCaseTest`（**新建**），Mockito mock 5+ 依赖（RateLimitService, SmsCodeService, AccountRepository, TokenIssuer, AccountStateMachine, TimingDefenseExecutor）。

**6 分支覆盖（红绿循环逐条）**：

- `should_return_tokens_when_phone_registered_and_code_valid()` — happy path
- `should_throw_RateLimitedException_when_phone_throttled()` — 限流
- `should_throw_InvalidCredentialsException_when_code_wrong()` — 码错
- `should_throw_InvalidCredentialsException_when_phone_not_registered()` — 未注册防枚举
- `should_throw_InvalidCredentialsException_when_account_FROZEN()` — 状态机
- `should_throw_when_token_signing_fails()` — TokenIssuer 异常 → 事务回滚

**Dependencies**: T1, T2, T3, T5 完成。

---

### T7 [App] RequestSmsCodeUseCase purpose 扩展（FR-009）

**File**: `mbw-account/src/main/java/com/mbw/account/application/usecase/RequestSmsCodeUseCase.java`（已有，扩展）

**Logic**:

- Command 加 `purpose: SmsCodePurpose enum { REGISTER, LOGIN }`，默认 REGISTER
- 分发逻辑：

```text
if (purpose == REGISTER) {
  if (phone 未注册) Template A (real code)
  else              Template B (already registered notice)
} else {  // LOGIN
  if (phone 已注册) Template A (real code)
  else              Template C (login on unregistered, advise register)
}
```

- Template C 阿里云 ID 配置项：`SMS_TEMPLATE_LOGIN_UNREGISTERED`
- **Fallback**：Template C 模板未审下来时，未注册路径**不发任何 SMS + pad time** 到已注册路径平均时延（防时延枚举）

**Test**: `RequestSmsCodeUseCaseTest`（已有，扩展）

- `should_send_template_A_when_register_purpose_and_phone_not_registered()`（已有）
- `should_send_template_B_when_register_purpose_and_phone_registered()`（已有）
- `should_send_template_A_when_login_purpose_and_phone_registered()`
- `should_send_template_C_when_login_purpose_and_phone_not_registered()`
- `should_pad_time_when_template_C_unavailable_and_phone_not_registered()`（fallback path）

**Dependencies**: 无 schema 改动；纯 application 层扩展。

---

### T8 [Web] LoginByPhoneSmsRequest + LoginResponse

**Files**:

- `mbw-account/src/main/java/com/mbw/account/web/request/LoginByPhoneSmsRequest.java`
- `mbw-account/src/main/java/com/mbw/account/web/response/LoginResponse.java`（**或** 提到 `mbw-shared.api.AuthTokenResponse` 共享，待 plan 决策）

**Logic**:

- `LoginByPhoneSmsRequest`: record(`@Pattern(...) String phone, @Pattern("\\d{6}") String code`) + `toCommand()` 方法
- `LoginResponse`: record(long accountId, String accessToken, String refreshToken)

**Test**: Validation 单测（覆盖 Pattern 失败 → 400）。

**Dependencies**: T5 完成。

---

### T9 [Web] AuthController 新建 + login-by-phone-sms endpoint

**File**: `mbw-account/src/main/java/com/mbw/account/web/controller/AuthController.java`（**新建**）

**Logic**: per plan.md § Web Layer，新建 controller，mapping `/api/v1/auth`，加 `loginByPhoneSms` 方法。Springdoc 注解齐（含 OpenAPI 描述 + 错误码 examples）。

**Test**: `AuthControllerLoginByPhoneSmsIT`（**新建**），`@WebMvcTest(AuthController.class)`：

- `should_return_200_when_valid_request()` — happy path（mock UseCase 返 result）
- `should_return_400_when_invalid_phone_format()` — Validation
- `should_return_400_when_invalid_code_format()` — Validation
- `should_return_401_when_InvalidCredentialsException_thrown()` — 错码 / 未注册
- `should_return_429_when_RateLimitedException_thrown()` — 限流
- `should_return_503_when_SmsSendException_thrown()` — gateway 失败（虽然 login 不主动发 SMS，但可能从 sms-codes 逻辑传染）

**Dependencies**: T6, T8 完成。

---

### T10 [Web] /sms-codes endpoint 接受 purpose 字段

**File**: `mbw-account/src/main/java/com/mbw/account/web/controller/AccountRegisterController.java`（已有，扩展）

**Logic**:

- Request DTO 加 `purpose` 字段（默认 "register"，向后兼容既有 register-by-phone E2E）
- Controller 把 purpose 传到 RequestSmsCodeUseCase
- Springdoc 注解 + OpenAPI 描述更新

**Test**: 既有 register E2E 测试不受影响（purpose 默认 register）；新建 `AuthSmsCodesIT`：

- `should_send_template_A_when_purpose_register_and_phone_not_registered()`（既有 register 路径）
- `should_send_template_C_when_purpose_login_and_phone_not_registered()`（新增）
- `should_default_to_register_when_purpose_omitted()`（向后兼容）

**Dependencies**: T7 完成。

---

### T11 [E2E] LoginByPhoneSmsE2EIT

**File**: `mbw-account/src/test/java/com/mbw/account/web/LoginByPhoneSmsE2EIT.java`（**新建**）

**Logic**: Testcontainers PG + Redis + MockServer (SMS gateway)。覆盖 spec.md 9 个 Acceptance Scenarios + SC-002（100 并发不同账号 0 错）+ SC-004（4 限流规则）。

**Test cases**：

- US1 (3 个 AS) + US2 (3 个 AS) + US3 (3 个 AS) = 9 个独立 scenarios
- SC-002: 100 个不同 ACTIVE 账号并发 login，0 错 + token count == 请求数
- SC-004: 60s 1 次 sms-codes / 24h 10 次 sms-codes / 24h 50 次 sms:`<ip>` / 24h 5 次 login 失败

**Fixture**: BeforeEach 起空 PG schema + 预注册 N 个 ACTIVE 账号；MockServer 拦截 SMS gateway 调用并断言 Template ID。

**Dependencies**: T0-T10 全部完成。

---

### T12 [Performance] LoginByPhoneSmsTimingDefenseIT

**File**: `mbw-account/src/test/java/com/mbw/account/web/LoginByPhoneSmsTimingDefenseIT.java`（**新建**）

**Logic**: 1000 次循环对比已注册 vs 未注册手机号 P95 时延差，断言 ≤ 50ms（per SC-003）。预热 + JVM warm-up + 第二轮才统计。

**Test cases**：

- `should_have_p95_latency_diff_under_50ms_for_registered_vs_unregistered()`
- 测试方法：N 次（recommend 1000）调 `/login-by-phone-sms`，记录 P95；对比已注册 vs 未注册 P95 差。

**Dependencies**: T0-T9 完成（与 T11 可并行）。

---

### T13 [E2E] CrossUseCaseEnumerationDefenseIT（新增）

**File**: `mbw-account/src/test/java/com/mbw/account/web/CrossUseCaseEnumerationDefenseIT.java`（**新建**）

**Logic**: per spec.md SC-005，跨 use case 防枚举验证。

**Test cases**：

- `should_have_byte_identical_response_register_already_vs_login_unregistered_vs_login_invalid_code()`
- 三场景断言响应（status / body / headers / P95 时延差）字节级一致

**Dependencies**: T11 完成。

---

### T14 [Contract] OpenAPI spec 验证

**File**: 无新文件；本任务是验证 Springdoc 自动生成的 OpenAPI spec 含新 endpoint。

**Logic**:

- 起本地 Spring Boot：`./mvnw spring-boot:run -pl mbw-app`
- `curl http://localhost:8080/v3/api-docs > /tmp/spec.json`
- 断言：`/api/v1/auth/login-by-phone-sms` 路径存在 + `/api/v1/accounts/sms-codes` 加 purpose 参数

**Test**: 手动验证 + 可选 `OpenApiSpecIT` 自动化（grep `/v3/api-docs` 输出含期望路径）。

**Dependencies**: T9, T10 完成。

---

## Parallel Opportunities

- **T0 / T1 / T2 / T3 / T5 同起**（彼此无依赖）
- **T4 在 T0+T1+T2 完成后**
- **T6 在 T1-T5 完成后**
- **T7 在 T0-T6 之外可并行**（独立 application 层）
- **T8-T9 在 T6 完成后**
- **T10 在 T7 完成后**
- **T11 / T12 / T13 在 T0-T10 全完成后**（可并行跑测）
- **T14 是 release 前最后一道关**

## Definition of Done

- ✅ 所有 14 任务的 unit + IT 测试 RED → GREEN
- ✅ `./mvnw -pl mbw-account verify` 全绿
- ✅ `./mvnw -pl mbw-app -Dtest=ModuleStructureTest test` 全绿（ArchUnit）
- ✅ Spotless + Checkstyle 全绿（CI required check）
- ✅ OpenAPI spec 含新 endpoint
- ✅ Cross-use-case enumeration defense 测试 GREEN（SC-005）
- ✅ Timing defense P95 时延差 ≤ 50ms（SC-003）

## Phasing PR 拆分

按 [meta plan file 拆 PR 顺序](https://github.com/xiaocaishen-michael/no-vain-years/blob/main/docs/plans/sdd-github-spec-kit-https-github-com-gi-drifting-rossum.md) Phase 1 server 部分：

- **PR 1（本 PR）**: `spec(account): login-by-phone-sms spec + plan + tasks`（仅文档）
- **PR 2**: `feat(account): impl login-by-phone-sms`（T0-T14 全部代码 + 测试 + migration）

PR 2 内部按依赖关系迭代提交（不强制 14 个 sub-PR），但 commit 历史应清晰展示 TDD 红绿循环。
